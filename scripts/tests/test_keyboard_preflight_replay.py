"""#276: redacted Galaxy v0.1.137 evidence cannot become a false typing PASS.

This is a metadata contract test, not a phone test, OCR, pixel ownership proof,
or a second implementation of Kotlin's native safety policy.
"""
import json
from pathlib import Path
import unittest


FIXTURE = (Path(__file__).resolve().parents[2] /
           "doc/dev/evidence/issue276/galaxy-v0137-preflight.json")


class KeyboardPreflightReplayEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_provenance_and_no_pretend_keyboard_execution(self):
        e = self.evidence
        self.assertEqual(1, e["schema_version"])
        self.assertEqual("0.1.137", e["immutable_release"]["version"])
        self.assertEqual(138, e["immutable_release"]["code"])
        self.assertFalse(e["before_editor_tap"]["actual_sequence_request_submitted"])
        self.assertFalse(e["after_editor_tap"]["actual_sequence_request_submitted"])
        self.assertEqual("NOT_ATTEMPTED", e["verdict"]["six_key_typing"])
        self.assertEqual("NOT_ATTEMPTED", e["verdict"]["mixed_trials"])
        self.assertEqual("NOT_MEASURED", e["verdict"]["measured_speed"])

    def test_minus_one_selection_is_not_independent_zero_caret_proof(self):
        e = self.evidence["before_editor_tap"]
        field = e["chrome_editor"]
        self.assertEqual("com.android.chrome:id/url_bar", field["resource_id"])
        self.assertEqual("", field["text"])
        self.assertTrue(field["focused"])
        self.assertEqual([-1, -1],
                         [field["raw_selection_start"], field["raw_selection_end"]])
        self.assertIsNone(e["cursor_native_proof"])
        self.assertEqual("bound_empty_editor", e["keyboard"]["selection_source"])
        self.assertTrue(e["keyboard"]["input_target_verified"])

    def test_popup_invalidates_initial_target_after_tap(self):
        before = self.evidence["before_editor_tap"]
        after = self.evidence["after_editor_tap"]
        self.assertNotEqual(before["selected_window_id"], after["selected_window_id"])
        self.assertIn("붙여넣기", after["floating_toolbar_labels"])
        self.assertFalse(after["keyboard_input_target_verified"])
        self.assertEqual("unverified", after["keyboard_selection_source"])

    def test_no_guessed_image_rect_and_no_claimed_oem_pixel_stage(self):
        e = self.evidence
        before = e["before_editor_tap"]
        image = before["image"]
        self.assertEqual([443, 960], [image["image_width"], image["image_height"]])
        self.assertEqual([1440, 3120], [image["source_width"], image["source_height"]])
        self.assertIsNone(image["exact_owned_pixel_regions"])
        keys = before["physical_key_candidates"]
        self.assertEqual({"ㄱ", "ㅏ", "ㄴ", "Space"}, {key["label"] for key in keys})
        self.assertEqual(4, len(keys))
        self.assertTrue(all(key["image_rect"] is None for key in keys))
        self.assertTrue(any(key["screen_bounds"][3] > image["image_height"] for key in keys))
        self.assertEqual("NOT_OBSERVED", e["verdict"]["pixel_failure_stage"])

    def test_settings_rollback_is_not_proof_screen_share_stopped(self):
        e = self.evidence["cleanup"]
        self.assertEqual([115, 116, 117],
                         [e["settings_revision_before"],
                          e["settings_revision_temporary"],
                          e["settings_revision_restored"]])
        self.assertTrue(e["original_settings_equivalent"])
        self.assertEqual("ended", e["control_session"])
        self.assertEqual("active", e["projection_lease_last_observed"])
        self.assertTrue(e["user_reported_projection_stopped"])
        self.assertFalse(e["independent_projection_stop_readback"])


if __name__ == "__main__":
    unittest.main()
