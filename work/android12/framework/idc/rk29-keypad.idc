# Rayo S1 ADC side keys.
# Android 12 InputReader only sets the wake policy flag for cursor motion,
# cursor buttons and non-media key presses on devices classed as external
# (CursorInputMapper / KeyboardInputMapper). The board reports this device as
# internal, so after GabOS switches the light source off at inactivity nothing
# would wake it. Classing it as external changes only the wake policy and the
# configuration presence flag; cursor mode and key layout are unchanged.
device.internal = 0
