from PIL import Image
from pathlib import Path
root = Path(__file__).parent
bg = Image.open(root / "src/main/resources/assets/everforge_mod/textures/gui/title/background.png")
logo = Image.open(root / "src/main/resources/assets/everforge_mod/textures/gui/title/logo.png")
assert bg.size == (1914, 1076), bg.size
assert logo.size == (1120, 350), logo.size
print("Title assets OK:", bg.size, logo.size)
