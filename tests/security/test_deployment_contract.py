"""Credential-free broker deployment contract (run from repository root)."""
import json
import unittest
from pathlib import Path


class BrokerDeploymentContract(unittest.TestCase):
    def test_legacy_checkout_is_routed_to_a_durable_dlq_without_redeclaring_source_queue(self):
        topology = json.loads(Path("deploy/ecs/rabbitmq/topology.json").read_text())
        source = next(q for q in topology["queues"] if q["name"] == "orders.checkout-requested.q")
        self.assertEqual(source["arguments"], {})
        policy = next((p for p in topology.get("policies", []) if p["name"] == "orders-checkout-dlq"), None)
        self.assertIsNotNone(policy, "A rejection must have a deployable dead-letter route")
        self.assertEqual(policy["pattern"], "^orders[.]checkout-requested[.]q$")
        definition = policy["definition"]
        self.assertEqual(definition["dead-letter-exchange"], "camisetas360.orders.dlx")
        self.assertEqual(definition["dead-letter-routing-key"], "checkout.requested.failed")
        dlq = next(q for q in topology["queues"] if q["name"] == "orders.checkout-requested.dlq")
        self.assertTrue(dlq["durable"])
        self.assertTrue(any(b["source"] == definition["dead-letter-exchange"]
            and b["destination"] == dlq["name"] and b["routing_key"] == definition["dead-letter-routing-key"]
            for b in topology["bindings"]))


if __name__ == "__main__":
    unittest.main()
