#!/usr/bin/env python3
"""Verify generated Maven dependency scopes for the six published Scala modules."""
from pathlib import Path
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
BACKENDS = ("dynamodb", "spanner", "bigtable", "sqlite")

for module in ("core", *BACKENDS, "."):
    directory = Path(module) / "target" / "scala-2.13"
    pom = max(directory.glob("*.pom"), key=lambda path: path.stat().st_mtime)
    root = ET.parse(pom).getroot()
    dependencies = {}
    for dependency in root.findall("m:dependencies/m:dependency", NS):
        group = dependency.findtext("m:groupId", namespaces=NS)
        artifact = dependency.findtext("m:artifactId", namespaces=NS)
        scope = dependency.findtext("m:scope", default="compile", namespaces=NS)
        if scope == "test" or artifact == "scala-library":
            continue
        assert group == "io.github.j5ik2o", (module, group, artifact, scope)
        dependencies[artifact] = scope
        if artifact.startswith("event-store-adapter-java"):
            assert dependency.findtext("m:version", namespaces=NS) == "5.2.0"
    if module == "core":
        expected = {"event-store-adapter-java-core": "compile"}
        expected.update({f"event-store-adapter-java-{backend}": "provided" for backend in BACKENDS})
    elif module == ".":
        expected = {f"event-store-adapter-scala-{backend}_2.13": "compile" for backend in ("core", *BACKENDS)}
    else:
        expected = {"event-store-adapter-scala-core_2.13": "compile", f"event-store-adapter-java-{module}": "compile"}
    assert dependencies == expected, (module, dependencies, expected)
    print(f"{module}: dependency scopes OK")
