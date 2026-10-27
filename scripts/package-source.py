"""Make a reproducible, allowlisted CodeBuild source ZIP without developer settings."""
import hashlib
import json
from pathlib import Path
import zipfile


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    files = [Path(name) for name in (
        "Dockerfile", ".dockerignore", "gradlew", "build.gradle", "settings.gradle", "buildspec.yml",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
        "src/main/resources/application.properties", "scripts/ecr-build.py", "scripts/container-smoke.sh")]
    for directory in ("src/main/java", "src/test/java"):
        files.extend(path.relative_to(root) for path in (root / directory).rglob("*.java"))
    for resource in (root / "src/main/resources").rglob("*"):
        if resource.is_file() and resource.name != "application.properties" and not resource.name.startswith("application-local."):
            raise RuntimeError("Review the source allowlist before packaging additional application resources")
    output = root / ".gradle/deployment"
    output.mkdir(parents=True, exist_ok=True)
    archive = output / "source.zip"
    manifest = []
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for relative in sorted(set(files)):
            path = root / relative
            if not path.resolve().is_relative_to(root) or path.is_symlink():
                raise RuntimeError("Source must be a regular file within this repository")
            data = path.read_bytes()
            if path.suffix != ".jar":
                data = data.replace(b"\r\n", b"\n")
            entry = zipfile.ZipInfo(relative.as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.create_system = 3
            entry.external_attr = (0o100755 if relative.as_posix().endswith(("gradlew", ".sh")) else 0o100644) << 16
            bundle.writestr(entry, data)
            manifest.append({"path": relative.as_posix(), "sha256": hashlib.sha256(data).hexdigest()})
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    result = {"sourceSha256": digest, "imageTag": "src-" + digest[:16], "files": manifest}
    (output / "source-manifest.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"archive": ".gradle/deployment/source.zip", "sourceSha256": digest,
                      "imageTag": result["imageTag"], "fileCount": len(manifest)}))


if __name__ == "__main__":
    main()
