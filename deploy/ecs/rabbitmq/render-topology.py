"""Render credential-free RabbitMQ definitions for the configured vhost."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--vhost", required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
definitions = json.loads(Path(__file__).with_name("topology.json").read_text())
definitions["vhosts"][0]["name"] = args.vhost
for section in ("exchanges", "queues", "bindings", "policies"):
    for entry in definitions[section]:
        entry["vhost"] = args.vhost
args.output.write_text(json.dumps(definitions, indent=2) + "\n", encoding="utf-8")
