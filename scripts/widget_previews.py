# Picker previews for the widgets whose look changed in the native app. 3x scale (1dp = 3px), Satoshi.
# Run from the repo root: python3 scripts/widget_previews.py
from PIL import Image, ImageDraw, ImageFont
F = "app/src/main/assets/fonts/Satoshi-"
OUT = "app/src/main/res/drawable-nodpi/"
BG, RAISED, TEXT, MUTED, ACCENT, INCOME = "#1A1614", "#2B2521", "#F3EBE2", "#AC9F92", "#FF7444", "#9BCB9F"
S = 3
def font(w, dp): return ImageFont.truetype(F + w + ".otf", int(dp * S))
def panel(wdp, hdp):
    im = Image.new("RGBA", (wdp * S, hdp * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, wdp * S - 1, hdp * S - 1], 22 * S, fill=BG)
    return im, d
def text(d, x, y, s, f, fill): d.text((x * S, y * S), s, font=f, fill=fill)

# Month pulse, 110x110dp: period pill, big spent, in and today.
im, d = panel(110, 110)
pill = "November"
pf = font("Medium", 12)
tw = d.textlength(pill, font=pf) / S
d.rounded_rectangle([14 * S, 14 * S, (14 + tw + 34) * S, (14 + 22) * S], 10 * S, fill=RAISED)
text(d, 24, 17.5, pill, pf, TEXT)
# Satoshi has no ↻: draw the cycle mark as an open ring with an arrowhead.
cx, cy, r = 14 + tw + 22, 25, 4.2
d.arc([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S], 300, 220, fill=TEXT, width=int(1.3 * S))
d.polygon([((cx + 1.2) * S, (cy - r - 2.2) * S), ((cx + 3.6) * S, (cy - r) * S), ((cx + 1.2) * S, (cy - r + 2.2) * S)], fill=TEXT)
text(d, 14, 40, "4.2M", font("Bold", 28), TEXT)
text(d, 14, 72, "spent", font("Regular", 11), MUTED)
text(d, 14, 86, "+8.5M in", font("Medium", 12), INCOME)
im.save(OUT + "widget_preview_month_pulse.png")

# To check, 180x110dp: header with count badge, the notification, the question, the answers.
im, d = panel(180, 110)
text(d, 14, 12, "To check", font("Bold", 13), TEXT)
bf = font("Bold", 11)
bw = d.textlength("3", font=bf) / S
d.rounded_rectangle([(166 - bw - 14) * S, 13 * S, 166 * S, 29 * S], 8 * S, fill=ACCENT)
text(d, 166 - bw - 7, 14.5, "3", bf, BG)
text(d, 14, 33, "Today, 12:41", font("Regular", 11), MUTED)
text(d, 14, 47, "QRIS Rp 25.000 to KOPI KEN…", font("Regular", 11), TEXT)
text(d, 14, 63, "Spent Rp 25.000 at Kopi?", font("Medium", 12), TEXT)
x = 14
for label, face, fg in [("Yes", TEXT, BG), ("Not money", RAISED, TEXT), ("Fix", RAISED, TEXT)]:
    f = font("Medium", 11)
    w = d.textlength(label, font=f) / S + 18
    d.rounded_rectangle([x * S, 82 * S, (x + w) * S, 100 * S], 9 * S, fill=face)
    text(d, x + 9, 85, label, f, fg)
    x += w + 6
im.save(OUT + "widget_preview_to_check.png")
