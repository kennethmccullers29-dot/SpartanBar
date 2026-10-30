# Sharing status-bar appearance with SpartanBar

SpartanBar now prefers a color supplied by the foreground app over screenshots.
The SpartanBar settings activity already supplies its dark background.
SpartanLauncher and other companion apps need to add this sender.

## Sender manifest

Install SpartanBar first. Sign both apps with the same signing certificate.
Add this permission to the companion app's manifest:

```xml
<uses-permission android:name="com.example.spartanbar.permission.SET_APPEARANCE" />
```

## Sender code

Send on Activity.onResume and whenever the intended top background or theme changes:

```kotlin
fun Context.shareStatusBarAppearance(color: Int, darkIcons: Boolean) {
    sendBroadcast(
        Intent("com.example.spartanbar.SET_APPEARANCE")
            .setPackage("com.example.spartanbar")
            .putExtra("source_package", packageName)
            .putExtra("color", color)
            .putExtra("dark_icons", darkIcons)
    )
}
```

Use the actual opaque color intended behind the status icons, rather than always
using the accent color. Set darkIcons to true for black icons on light backgrounds.
Omit dark_icons to let SpartanBar choose based on the supplied color's luminance.
Transparent colors are treated as opaque RGB: supply the composited background color.

The receiver persists the latest appearance per package, applies it only while
that package is foreground, and immediately refreshes a running accessibility
service. Background apps cannot change the currently displayed color.
The signature permission restricts this handoff to apps signed with the same certificate.

## Fallback behavior

Apps without a shared appearance use a 3×3 median sample in the actual system
status-bar region. The overlay clears that region after drawing all icons so its
own contrast colors cannot feed back into matching. Sampling pauses during scrolling
and resumes after 900 ms of quiet. Stable samples are confirmed before being applied;
colors are not permanently frozen. Screenshots are never saved.
Secure screens retain the previous color.
YouTube keeps a dark background to avoid tracking video frames.

Automatic fullscreen hiding remains disabled after the earlier visibility regression.
This handoff shares background and icon brightness only.

## Validation

Run :app:testDebugUnitTest and :app:assembleDebug.
On a device, open SpartanBar, then a light third-party app, then YouTube.
Confirm the direct dark appearance, sampled light appearance, and stable video appearance.
After integrating the launcher sender, change its intended top-surface color
and confirm SpartanBar follows it without screenshot sampling.
