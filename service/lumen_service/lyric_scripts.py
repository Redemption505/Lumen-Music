"""Small, explicit script equivalences for common Chinese music metadata.

This is deliberately not a general Chinese converter: lyrics are returned in
their original script, and only these unambiguous pairs affect comparison and
the preference for simplified candidates.
"""

_PAIRS = (
    "戀恋榮荣過过間间傑杰倫伦愛爱說说聽听風风雲云夢梦時时"
    "會会們们與与萬万葉叶劉刘張张陳陈鄧邓楊杨華华蕭萧韋韦"
    "藝艺樂乐東东飛飞輝辉龍龙齊齐遠远開开關关從从來来這这"
    "個个為为還还讓让點点裡里裏里離离見见覺觉錯错淚泪傷伤"
    "聲声響响輕轻無无後后總总終终滿满願愿經经緣缘歲岁長长"
    "顏颜紅红藍蓝綠绿黃黄溫温熱热陽阳陰阴陰阴體体變变實实"
    "寫写讀读記记識识誰谁該该語语謊谎話话證证謝谢請请難难"
    "對对隻只種种邊边處处遲迟遙遥進进轉转運运連连達达選选"
    "國国電电視视劇剧帶带動动靜静隨随斷断擁拥抱抱隱隐頭头"
    "親亲習习傳传統统純纯當当尋寻餘余響响閃闪燈灯羅罗蘇苏"
    "溼湿濕湿"
)

TRADITIONAL_TO_SIMPLIFIED = {a: b for a, b in zip(_PAIRS[::2], _PAIRS[1::2]) if a != b}
# Prefer the first explicitly listed traditional spelling when a simplified
# character has more than one traditional spelling.
SIMPLIFIED_TO_TRADITIONAL: dict[str, str] = {}
for _traditional, _simplified in TRADITIONAL_TO_SIMPLIFIED.items():
    SIMPLIFIED_TO_TRADITIONAL.setdefault(_simplified, _traditional)


def simplified(value: str) -> str:
    return value.translate(str.maketrans(TRADITIONAL_TO_SIMPLIFIED))


def traditional(value: str) -> str:
    return value.translate(str.maketrans(SIMPLIFIED_TO_TRADITIONAL))


def script_preference(value: str) -> int:
    """Simplified/neutral first; never rewrite the original lyric content."""
    return int(any(character in TRADITIONAL_TO_SIMPLIFIED for character in value))
