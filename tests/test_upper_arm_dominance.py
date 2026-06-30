import unittest

from work.scripts.build_2d_action_review import summarize_upper_arm_dominance


class UpperArmDominanceTests(unittest.TestCase):
    def test_ratio_increases_when_upper_arm_energy_dominates(self):
        upper_arm_heavy = [
            {
                "upper_arm_angular_speed_deg_s": 600,
                "forearm_angular_speed_deg_s": 120,
                "normalized_wrist_speed_body_s": 1.2,
                "active_arm_confidence": 0.9,
            },
            {
                "upper_arm_angular_speed_deg_s": 720,
                "forearm_angular_speed_deg_s": 140,
                "normalized_wrist_speed_body_s": 1.4,
                "active_arm_confidence": 0.9,
            },
        ]
        whip_heavy = [
            {
                "upper_arm_angular_speed_deg_s": 180,
                "forearm_angular_speed_deg_s": 520,
                "normalized_wrist_speed_body_s": 6.5,
                "active_arm_confidence": 0.9,
            },
            {
                "upper_arm_angular_speed_deg_s": 210,
                "forearm_angular_speed_deg_s": 610,
                "normalized_wrist_speed_body_s": 7.2,
                "active_arm_confidence": 0.9,
            },
        ]

        upper = summarize_upper_arm_dominance(upper_arm_heavy)
        whip = summarize_upper_arm_dominance(whip_heavy)

        self.assertGreater(upper["ratio"], whip["ratio"])
        self.assertGreaterEqual(upper["ratio"], 0)
        self.assertLessEqual(upper["ratio"], 100)
        self.assertEqual(upper["label"], "high")
        self.assertEqual(whip["label"], "low")

    def test_ratio_reports_low_reliability_when_arm_confidence_is_low(self):
        result = summarize_upper_arm_dominance(
            [
                {
                    "upper_arm_angular_speed_deg_s": 500,
                    "forearm_angular_speed_deg_s": 100,
                    "normalized_wrist_speed_body_s": 1.0,
                    "active_arm_confidence": 0.2,
                }
            ]
        )

        self.assertEqual(result["reliability"], "low")
        self.assertGreaterEqual(result["ratio"], 0)
        self.assertLessEqual(result["ratio"], 100)

    def test_no_elbow_pause_raises_upper_arm_dominance_evidence(self):
        no_pause = [
            {
                "upper_arm_angular_speed_deg_s": 420,
                "forearm_angular_speed_deg_s": 300,
                "normalized_wrist_speed_body_s": 3.0,
                "elbow_angular_speed_deg_s": 260,
                "active_arm_confidence": 0.8,
            }
            for _ in range(8)
        ]
        with_pause = [
            {
                "upper_arm_angular_speed_deg_s": 420,
                "forearm_angular_speed_deg_s": 300,
                "normalized_wrist_speed_body_s": 3.0,
                "elbow_angular_speed_deg_s": elbow_speed,
                "active_arm_confidence": 0.8,
            }
            for elbow_speed in (260, 240, 40, 25, 30, 45, 230, 250)
        ]

        no_pause_result = summarize_upper_arm_dominance(no_pause)
        with_pause_result = summarize_upper_arm_dominance(with_pause)

        self.assertGreater(no_pause_result["ratio"], with_pause_result["ratio"])
        self.assertGreater(no_pause_result["elbow_no_pause_score"], with_pause_result["elbow_no_pause_score"])
        self.assertEqual(no_pause_result["elbow_pause_detected"], False)
        self.assertEqual(with_pause_result["elbow_pause_detected"], True)


if __name__ == "__main__":
    unittest.main()
