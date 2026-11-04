"""Every launcher animation or effect follows Launcher settings: basic motion stops with Animations off, and the
heavier decorative effects run only with Advanced effects on (which needs Animations); none runs under reduced motion.
Source checks, like test_top_bar_launch_actions; no device access."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'launcher/app/src/main/java/com/gathin/tvhome'


def body(source, signature, end):
    return source.split(signature, 1)[1].split(end, 1)[0]


class MotionGatesTest(unittest.TestCase):
    def setUp(self):
        self.main = (JAVA / 'MainActivity.java').read_text()

    def test_advanced_effects_needs_animations(self):
        self.assertIn('advancedEffects = cfg.animations && cfg.advancedEffects;', self.main)

    def test_menus_unfold_and_fold_only_with_animations(self):
        drop_in = body(self.main, 'private void dropIn(', 'private View addSeparator(')
        self.assertLess(drop_in.index('if (!animations)'), drop_in.index('panel.animate()'))
        dismiss = body(self.main, 'public void dismiss() {', 'void dismissNow()')
        self.assertIn('if (!animations || !isShowing()', dismiss)

    def test_wifi_names_scroll_only_with_animations(self):
        self.assertIn('if (animations) scrollNameOnFocus(row);', self.main)

    def test_reorder_shake_is_an_advanced_effect(self):
        start = body(self.main, 'private void startWiggle() {', 'private void stopWiggle()')
        self.assertIn('!advancedEffects || reducedMotion', start)

    def test_glow_drift_follows_animations(self):
        self.assertIn('glowDrift = cfg.glowDrift && cfg.ambientGlow && animations && !reducedMotion;', self.main)

    def test_recent_apps_panel_motion_follows_animations(self):
        switcher = (JAVA / 'SwitcherActivity.java').read_text()
        self.assertIn('animations = cfg.animations && !cfg.reducedMotion;', switcher)


if __name__ == '__main__':
    unittest.main()
