
from flytekit import task, workflow


@task
def say_hello(name: str) -> str:
    return f"Helloooooo2, {name}!"
