"""UI wiring for the policy exercised by LaunchActionsTest on the JVM; no device access."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'launcher/app/src/main/java/com/gathin/tvhome'


class TopBarLaunchActionsTest(unittest.TestCase):
    def setUp(self):
        self.source = (JAVA / 'MainActivity.java').read_text()
        self.method = self.source.split('private void addTopBarApps(', 1)[1].split(
            '@Override public void onLibraryUp()', 1)[0]
        # The launch policy both the top bar and the gear menu's app rows share.
        self.helper = self.source.split('private static Intent appLaunchIntent(', 1)[1].split(
            'private void addTopBarApps(', 1)[0]
        self.menu = self.source.split('private void addMenuApps(', 1)[1].split(
            'private void addSettingsMenuRow(', 1)[0]

    def test_top_bar_passes_configured_actions_to_tested_selection_policy(self):
        self.assertIn('addTopBarApps(cfg.topBarApps, cfg.launchActions)', self.source)
        self.assertIn('appLaunchIntent(pm, pkg, launchActions)', self.method)
        self.assertIn('LaunchActions.select(pkg, launchActions, launch,', self.helper)
        self.assertIn('button.setOnClickListener(v -> open(new Intent(target)))', self.method)

    def test_gear_menu_apps_use_the_same_policy(self):
        self.assertIn('menuLaunchActions = cfg.launchActions', self.source)
        self.assertIn('appLaunchIntent(pm, pkg, menuLaunchActions)', self.menu)
        self.assertIn('open(new Intent(launch))', self.menu)

    def test_action_is_package_scoped_resolved_and_does_not_replace_missing_apps(self):
        self.assertIn('new Intent(action).setPackage(targetPackage)', self.helper)
        self.assertIn('pm.resolveActivity(custom, 0) != null ? custom : null', self.helper)
        self.assertLess(self.helper.index('if (launch == null) return null'),
                        self.helper.index('LaunchActions.select'))
        self.assertIn('getLeanbackLaunchIntentForPackage(pkg)', self.helper)
        self.assertIn('getLaunchIntentForPackage(pkg)', self.helper)
        self.assertIn('if (launch == null) continue', self.method)
        self.assertIn('if (launch == null) continue', self.menu)
