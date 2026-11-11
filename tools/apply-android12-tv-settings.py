#!/usr/bin/env python3
"""Use native TV settings with an opaque window and no board-side overlay animation."""
import argparse
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);a=p.parse_args()
base=a.source/'packages/apps/TvSettings/Settings'
path=base/'res/values/styles.xml';text=path.read_text()
before='<style name="TvSettingsActivityTheme" parent="Theme.Settings.Transparent" />'
after='''<style name="TvSettingsActivityTheme" parent="Theme.Settings">
        <item name="android:windowIsTranslucent">false</item>
        <item name="android:windowBackground">@color/settings_background</item>
        <item name="android:backgroundDimEnabled">false</item>
    </style>'''
if after not in text:
    if text.count(before)!=1:raise SystemExit('Pinned TV settings theme differs')
    text=text.replace(before,after)
path.write_text(text)
path=base/'src/com/android/tv/settings/TvSettingsActivity.java';text=path.read_text()
text=text.replace('import android.os.Bundle;','import android.os.Bundle;\nimport android.os.SystemProperties;') if 'import android.os.SystemProperties;' not in text else text
anchor='            if (FlavorUtils.isTwoPanel(this)) {'
addition='''            if (SystemProperties.getBoolean("ro.gabos.settings.opaque", false)) {
                getSupportFragmentManager().beginTransaction()
                        .add(android.R.id.content, fragment, SETTINGS_FRAGMENT_TAG).commitNow();
                return;
            }
'''
if addition not in text:
    if text.count(anchor)!=1:raise SystemExit('Pinned settings entry differs')
    text=text.replace(anchor,addition+anchor)
anchor='    public void finish() {\n'
addition='''        if (SystemProperties.getBoolean("ro.gabos.settings.opaque", false)) {
            super.finish();
            return;
        }
'''
if anchor+addition not in text:
    if text.count(anchor)!=1:raise SystemExit('Pinned settings exit differs')
    text=text.replace(anchor,anchor+addition)
path.write_text(text)
print('Native TV settings: opaque background and board-scoped direct transitions.')
