"""CodeBuild-only registry helper. Never print or persist authorization tokens."""
import argparse
import base64
import os
import subprocess

import boto3


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["login", "digest"])
    args = parser.parse_args()
    if not os.environ.get("CODEBUILD_BUILD_ID"):
        raise RuntimeError("Run this helper only in the configured CodeBuild project")
    repository = os.environ["ECR_REPOSITORY_URI"]
    registry, name = repository.split("/", 1)
    # CodeBuild uses its assigned service role, not a developer's local AWS profile.
    ecr = boto3.client("ecr", region_name="ap-southeast-2")
    if args.action == "login":
        credentials = ecr.get_authorization_token()["authorizationData"][0]
        if credentials["proxyEndpoint"] != "https://" + registry:
            raise RuntimeError("Unexpected registry endpoint")
        username, password = base64.b64decode(credentials["authorizationToken"]).decode().split(":", 1)
        subprocess.run(["docker", "login", "--username", username, "--password-stdin", registry],
                       input=password, text=True, check=True)
    else:
        image = ecr.describe_images(repositoryName=name, imageIds=[{"imageTag": os.environ["IMAGE_TAG"]}])["imageDetails"][0]
        print(repository + "@" + image["imageDigest"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
