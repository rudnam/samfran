# Converts the website's Plus Jakarta Sans (web/public/fonts/plus-jakarta-sans-latin.woff2,
# a variable font) into the static TTFs the app uses (core/designsystem/src/main/res/font):
# the weights the website's styles use. Like the website, only Latin: Japanese comes
# from the phone's own font. As RudWallet's android/fonts.py does for Inter.
#
#   python3 -m venv /tmp/fonts && /tmp/fonts/bin/pip install fonttools brotli
#   /tmp/fonts/bin/python android/fonts.py
import os

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, "web", "public", "fonts", "plus-jakarta-sans-latin.woff2")
out = os.path.join(root, "android", "core", "designsystem", "src", "main", "res", "font")
os.makedirs(out, exist_ok=True)

for weight, name in [(400, "jakarta_regular"), (600, "jakarta_semibold"), (700, "jakarta_bold"), (800, "jakarta_extrabold")]:
    static = instancer.instantiateVariableFont(TTFont(src), {"wght": weight})
    static.flavor = None
    static.save(os.path.join(out, name + ".ttf"))
    print(name, weight)
