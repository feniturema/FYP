#!/usr/bin/env python3
"""Generate the P5a demo catalogue deterministically (docs/phases/P5a.md §1, §6.2, acceptance A5).

    python3 scripts/p5a/gen_catalog.py [--out backend/src/main/resources/demo/catalog.json]

400 products (ids 1001-1400, 40 per category) and 200 second-hand items (ids 5001-5200, 20 per category, sellers
seller01-seller20). Languages en / ms / zh = 6:2:2. Each product picks one synonym of its noun group for the name
("earbuds" vs "earphones", "fon kepala" vs "earfon", "耳机" vs "耳麦"), so a synonym query does not always match
lexically: that is the case the P5b vector search is meant to fix. Uses random.Random(42) only; the output is
byte-identical on every run. Standard library only.
"""
import argparse
import json
import random

# category -> {lang -> [synonym groups]}, adjectives per lang, price range (RM)
CATALOG = {
    "Apparel": {
        "en": [["hoodie", "sweatshirt", "jumper"], ["t-shirt", "tee"], ["jacket", "windbreaker"], ["cap", "baseball hat"]],
        "ms": [["baju hoodie", "baju sejuk"], ["baju-t", "kemeja-t"], ["jaket", "baju kalis angin"], ["topi", "kep"]],
        "zh": [["连帽衫", "卫衣"], ["T恤", "短袖衫"], ["夹克", "风衣"], ["鸭舌帽", "棒球帽"]],
        "price": (15, 120),
    },
    "Accessories": {
        "en": [["backpack", "rucksack", "school bag"], ["lanyard", "neck strap"], ["wallet", "purse"], ["umbrella", "parasol"]],
        "ms": [["beg galas", "beg sekolah"], ["tali leher kad", "lanyard"], ["dompet", "beg duit"], ["payung"]],
        "zh": [["双肩包", "背包", "书包"], ["挂绳", "证件绳"], ["钱包", "卡包"], ["雨伞", "折叠伞"]],
        "price": (8, 150),
    },
    "Lifestyle": {
        "en": [["tumbler", "travel mug", "insulated cup"], ["water bottle", "flask"], ["lunch box", "food container"], ["keychain", "key ring"]],
        "ms": [["botol air", "bekas air"], ["mug perjalanan", "cawan bertebat"], ["bekas makanan", "kotak bekal"], ["rantai kunci"]],
        "zh": [["保温杯", "随行杯"], ["水壶", "水瓶"], ["饭盒", "便当盒"], ["钥匙扣", "钥匙圈"]],
        "price": (6, 90),
    },
    "Electronics": {
        "en": [["earphones", "earbuds", "headphones"], ["power bank", "portable charger"], ["mouse", "wireless mouse"], ["laptop stand", "notebook riser"]],
        "ms": [["fon kepala", "earfon"], ["bank kuasa", "pengecas mudah alih"], ["tetikus", "tetikus tanpa wayar"], ["dirian komputer riba"]],
        "zh": [["蓝牙耳机", "耳机", "耳麦"], ["充电宝", "移动电源"], ["无线鼠标", "鼠标"], ["笔记本电脑支架", "电脑支架"]],
        "price": (20, 450),
    },
    "Books": {
        "en": [["textbook", "course book"], ["novel", "fiction book"], ["dictionary", "lexicon"], ["exam guide", "revision book"]],
        "ms": [["buku teks", "buku rujukan"], ["novel"], ["kamus"], ["buku latihan", "panduan peperiksaan"]],
        "zh": [["教科书", "课本"], ["小说"], ["词典", "字典"], ["复习指南", "考试辅导书"]],
        "price": (10, 180),
    },
    "Stationery": {
        "en": [["notebook", "exercise book"], ["ballpoint pen", "pen"], ["highlighter", "marker"], ["pencil case", "pen pouch"]],
        "ms": [["buku nota", "buku tulis"], ["pen mata bulat", "pen"], ["pen penyerlah"], ["bekas pensel", "kotak pensel"]],
        "zh": [["笔记本", "练习本"], ["圆珠笔", "中性笔"], ["荧光笔", "记号笔"], ["笔袋", "文具盒"]],
        "price": (2, 40),
    },
    "Sports": {
        "en": [["running shoes", "sneakers", "trainers"], ["badminton racket", "racquet"], ["yoga mat", "exercise mat"], ["football", "soccer ball"]],
        "ms": [["kasut sukan", "kasut larian"], ["raket badminton"], ["tikar yoga", "tikar senaman"], ["bola sepak"]],
        "zh": [["跑鞋", "运动鞋"], ["羽毛球拍", "球拍"], ["瑜伽垫", "健身垫"], ["足球"]],
        "price": (12, 400),
    },
    "Food": {
        "en": [["instant noodles", "cup noodles"], ["coffee", "instant coffee"], ["snack box", "biscuits"], ["energy drink", "isotonic drink"]],
        "ms": [["mi segera", "mee segera"], ["kopi", "kopi segera"], ["biskut", "kotak snek"], ["minuman tenaga", "minuman isotonik"]],
        "zh": [["方便面", "泡面"], ["咖啡", "速溶咖啡"], ["饼干", "零食礼盒"], ["能量饮料", "运动饮料"]],
        "price": (2, 60),
    },
    "Home": {
        "en": [["desk lamp", "reading light"], ["pillow", "cushion"], ["blanket", "throw"], ["clothes hanger", "coat hanger"]],
        "ms": [["lampu meja", "lampu belajar"], ["bantal"], ["selimut"], ["penyangkut baju"]],
        "zh": [["台灯", "护眼灯"], ["枕头", "靠垫"], ["毛毯", "被子"], ["衣架"]],
        "price": (5, 160),
    },
    "Beauty": {
        "en": [["sunscreen", "sunblock"], ["face wash", "facial cleanser"], ["lip balm", "lip care"], ["moisturiser", "face cream"]],
        "ms": [["pelindung matahari", "losyen pelindung"], ["pencuci muka"], ["pelembap bibir"], ["pelembap muka", "krim muka"]],
        "zh": [["防晒霜", "防晒乳"], ["洗面奶", "洁面乳"], ["润唇膏"], ["保湿霜", "面霜"]],
        "price": (5, 120),
    },
}

ADJ = {
    "en": ["Navy", "Black", "White", "Grey", "Maroon", "Compact", "Lightweight", "Premium", "Classic", "Eco"],
    "ms": ["biru", "hitam", "putih", "kelabu", "merah", "kecil", "ringan", "premium", "klasik", "mesra alam"],
    "zh": ["藏青色", "黑色", "白色", "灰色", "酒红色", "小巧", "轻便", "高级", "经典", "环保"],
}
PRODUCT_DESC = {
    "en": ["{name} from the FTSM store, made for everyday campus life.",
           "Official {name}. Durable, easy to carry between lectures.",
           "{name} with the UKM faculty logo. Popular with first-year students."],
    "ms": ["{name} dari kedai rasmi FTSM, sesuai untuk kehidupan kampus.",
           "{name} rasmi. Tahan lama dan mudah dibawa ke kuliah.",
           "{name} dengan logo fakulti UKM. Pilihan pelajar tahun pertama."],
    "zh": ["FTSM 官方商店的{name}，适合日常校园生活。",
           "官方{name}，耐用，方便上课携带。",
           "印有 UKM 学院标志的{name}，深受新生欢迎。"],
}
ITEM_DESC = {
    "en": ["Selling my {name}, {cond}. Pick up at the faculty lobby.",
           "{name} in {cond} condition, used for one semester.",
           "Pre-owned {name}, {cond}. Price negotiable for students."],
    "ms": ["Jual {name} saya, keadaan {cond}. Ambil di lobi fakulti.",
           "{name} keadaan {cond}, digunakan satu semester.",
           "{name} terpakai, {cond}. Harga boleh runding."],
    "zh": ["出售我的{name}，{cond}，学院大厅自取。",
           "{name}，{cond}，只用了一个学期。",
           "二手{name}，{cond}，学生价可议。"],
}
COND_TEXT = {
    "NEW": {"en": "unused", "ms": "belum digunakan", "zh": "全新未用"},
    "LIKE_NEW": {"en": "like new", "ms": "seperti baru", "zh": "九成新"},
    "USED": {"en": "used", "ms": "terpakai", "zh": "有使用痕迹"},
}
LANG_PATTERN = ["en", "en", "ms", "en", "zh", "en", "en", "ms", "en", "zh"]   # 6:2:2 in every block of 10
CONDITIONS = ["NEW", "LIKE_NEW", "USED"]


def name_for(rng, lang, groups):
    noun = rng.choice(rng.choice(groups))
    adj = rng.choice(ADJ[lang])
    if lang == "en":
        return f"{adj} {noun.title() if noun.islower() else noun}"
    if lang == "ms":
        return f"{noun.capitalize()} {adj}"
    return f"{adj}{noun}"


def price(rng, lo, hi, scale=1.0):
    """RM price ending in .90, at least 1.90."""
    return round(max(1, int(rng.uniform(lo, hi) * scale)) + 0.9, 2)


def generate():
    rng = random.Random(42)
    products, items = [], []
    pid, iid, n = 1001, 5001, 0
    for category, spec in CATALOG.items():
        lo, hi = spec["price"]
        for _ in range(40):
            lang = LANG_PATTERN[n % 10]
            n += 1
            name = name_for(rng, lang, spec[lang])
            products.append({
                "id": pid, "lang": lang, "name": name,
                "description": rng.choice(PRODUCT_DESC[lang]).format(name=name),
                "category": category, "price": price(rng, lo, hi), "totalStock": rng.randint(5, 300),
                "imageUrl": None,
            })
            pid += 1
    n = 0
    for category, spec in CATALOG.items():
        lo, hi = spec["price"]
        for _ in range(20):
            lang = LANG_PATTERN[n % 10]
            seller = f"seller{n % 20 + 1:02d}"
            n += 1
            title = name_for(rng, lang, spec[lang])
            cond = rng.choice(CONDITIONS)
            items.append({
                "id": iid, "lang": lang, "title": title,
                "description": rng.choice(ITEM_DESC[lang]).format(name=title, cond=COND_TEXT[cond][lang]),
                "category": category, "condition": cond, "price": price(rng, lo, hi, 0.6),
                "sellerKey": seller, "imageUrl": None,
            })
            iid += 1
    return {"version": 1, "generator": "scripts/p5a/gen_catalog.py (random.Random(42))",
            "products": products, "items": items}


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default="backend/src/main/resources/demo/catalog.json")
    a = ap.parse_args()
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(generate(), ensure_ascii=False, indent=1) + "\n")


if __name__ == "__main__":
    main()
