#!/usr/bin/env python3
"""Preserve native TV transparency and restore the upstream overlay transitions."""
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
if after in text:text=text.replace(after,before)
if text.count(before)!=1:raise SystemExit('Pinned transparent TV settings theme differs')
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
text=text.replace(addition,'')
anchor='    public void finish() {\n'
addition='''        if (SystemProperties.getBoolean("ro.gabos.settings.opaque", false)) {
            super.finish();
            return;
        }
'''
text=text.replace(anchor+addition,anchor)
if 'SystemProperties.' not in text:text=text.replace('import android.os.SystemProperties;\n','')
path.write_text(text)
print('Native TV settings: original translucent theme and transitions retained.')
