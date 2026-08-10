#!/usr/bin/env python3
"""gen-routes.py — regenerate backend-go/internal/surface/routes_gen.go from the
public_api FileDescriptorProto text dump.

Usage:
    gen-routes.py <descriptor.txt> <output.go>

The descriptor is produced by re/tools/dumpproto (FileDescriptorProto text
format). This script decodes the service method table and the google.api.http
annotations (REST path mapping) into a Go literal.
"""
import re
import sys


def main() -> None:
    src, out = sys.argv[1], sys.argv[2]
    txt = open(src).read()
    s = txt[txt.find("service {"):]
    methods = []
    for m in re.finditer(
        r'method \{\n\s+name: "(\w+)"\n\s+input_type: "([^"]+)"\n\s+output_type: "([^"]+)"',
        s,
    ):
        name, it, ot = m.groups()
        seg = s[m.end(): m.end() + 400]
        h = re.search(r'(post|get|delete|put|patch): "([^"]+)"', seg)
        methods.append((name, it.split(".")[-1], ot.split(".")[-1], h.group(2) if h else ""))
    methods.sort(key=lambda x: x[0])

    lines = [
        "// Code generated from services_api_proxy_public_api.proto via scripts/gen-routes.py. DO NOT EDIT.",
        "package surface",
        "",
        "// Method is one RPC of the public_api.Augment service (grpc-gateway annotated).",
        "type Method struct {",
        "\tName   string // RPC name, e.g. ChatStream",
        "\tPath   string // grpc-gateway REST path, e.g. /chat-stream",
        "\tInput  string // request message name",
        "\tOutput string // response message name",
        "}",
        "",
        f"// Routes is the full public_api.Augment method table ({len(methods)} RPCs).",
        "var Routes = []Method{",
    ]
    for name, it, ot, path in methods:
        p = path or "-"
        lines.append(f'\t{{Name: "{name}", Path: "{p}", Input: "{it}", Output: "{ot}"}},')
    lines.append("}")
    lines.append("")
    with open(out, "w") as f:
        f.write("\n".join(lines))
    print(f"wrote {len(methods)} methods -> {out}")


if __name__ == "__main__":
    main()
