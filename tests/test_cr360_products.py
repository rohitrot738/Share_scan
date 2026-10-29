"""What a company makes: the profile text a Screener page carries, and the products read from it."""
from datetime import date, timedelta
from pathlib import Path

from cr360 import app_store, products, screener_page
from cr360.products import FINANCIAL, IT, classify

PROFILE = """
<div class="company-profile">
  <div class="flex-column">
    <div class="title">About</div>
    <div class="sub show-more-box about" style="flex-basis: 100px">
      <p>MRF Ltd is the largest manufacturer of tyres in India.<sup><a href="#">[1]</a></sup></p>
    </div>
  </div>
  <div class="flex-column">
    <div class="title">Key Points</div>
    <div class="sub commentary always-show-more-box">
      <p><strong>Product Portfolio</strong><br>
      The company makes tyres for passenger cars, trucks, buses and tractors, and also paints and toys.<sup>[2]</sup></p>
      <div class="show-more-button">more</div>
    </div>
  </div>
</div>
"""


def test_a_page_keeps_the_about_and_key_points_text_without_source_marks():
    p = screener_page.parse(f"<html><body><h1>MRF Ltd</h1>{PROFILE}</body></html>")
    assert p["about"] == "MRF Ltd is the largest manufacturer of tyres in India."
    assert p["points"].startswith("Product Portfolio\nThe company makes tyres for passenger cars")
    assert "[2]" not in p["points"]


def test_a_page_without_a_profile_has_empty_text():
    p = screener_page.parse("<html><body><h1>X Ltd</h1></body></html>")
    assert p["about"] == "" and p["points"] == ""


def test_long_profile_text_is_cut_on_a_word():
    words = "steel " * 600
    p = screener_page.parse(f'<div class="sub show-more-box about"><p>{words}</p></div>')
    assert len(p["about"]) <= screener_page.PROFILE_CHARS and p["about"].endswith("steel")


def test_products_come_in_the_order_the_text_names_them():
    assert classify("MRF Ltd is the largest manufacturer of tyres in India.",
                    "The company makes tyres for passenger cars, trucks, buses and tractors, and also paints and toys.",
                    "Automobile and Auto Components") == ["tyre", "paint", "toys"]


def test_who_the_products_are_for_is_not_a_product():
    made = classify("Bharat Forge makes forgings and engine components used in commercial vehicles and aircraft.",
                    sector="Automobile and Auto Components")
    assert "autoparts" in made and "truck" not in made and "aircraft" not in made
    # "known for" says what it makes, not who it sells to.
    assert classify("Dabur is known for ayurvedic medicines and hair oils.", sector="Fast Moving Consumer Goods")[0] == "ayurveda"


def test_the_industry_narrows_what_a_company_can_make():
    # A bank's car loans are loans, not cars.
    assert classify("HDFC Bank offers car loans, home loans, credit cards and insurance.", sector=FINANCIAL) == \
        ["bank", "loans", "payments", "insurance"]
    # An IT company's banking clients do not make it a bank.
    assert classify("Infosys provides software services and digital consulting to banking clients.", sector=IT) == ["software"]
    # A carmaker is not a lender, even when it mentions finance.
    assert "loans" not in classify("Maruti makes cars and offers car finance through partner banks.",
                                   sector="Automobile and Auto Components")
    # Without a known industry nothing is ruled out.
    assert "bank" in classify("A small bank in Kerala.")


def test_overlapping_words_name_one_product():
    assert classify("It sells edible oil, rice and atta.", sector="Fast Moving Consumer Goods")[:2] == ["edibleoil", "grains"]
    assert "oil" not in classify("It sells edible oil.", sector="Fast Moving Consumer Goods")
    made = classify("Whirlpool makes washing machines and refrigerators.", sector="Consumer Durables")
    assert made == ["appliance"]


def test_at_most_eight_products_and_only_known_keys():
    text = ("It makes cars, bikes, trucks, buses, tractors, tyres, batteries, paints, cement, steel and toys.")
    made = classify(text, sector="Automobile and Auto Components")
    assert len(made) == products.MAX_PRODUCTS and set(made) <= set(products.KEYS)
    assert classify("", "", "") == []


def test_the_app_row_carries_the_products():
    store = app_store.empty()
    page = screener_page.parse((Path(__file__).parent / "fixtures" / "cr360" / "screener_reliance.html").read_text())
    page["about"] = "Reliance makes petrochemicals, refines crude oil and runs Jio, a telecom business, and retail stores."
    app_store.put_page(store, "RELIANCE", page, date(2026, 10, 21), "consolidated")
    stock = store["stocks"]["RELIANCE"]
    assert stock["about"].startswith("Reliance makes") and stock["pv"] == app_store.PAGE_VERSION
    prices = [{"date": (date(2026, 10, 21) - timedelta(days=400 - i)).isoformat(), "high": 1005.0, "low": 995.0, "close": 1000.0}
              for i in range(400)]
    rec = app_store.app_record(stock, app_store.research_input("RELIANCE", stock, prices, None), "Oil Gas & Consumable Fuels")
    assert rec["pr"] == ["oil", "telecom", "retail"]
    # A later page without the text keeps what was read before.
    page["about"] = ""
    app_store.put_page(store, "RELIANCE", page, date(2026, 10, 28), "consolidated")
    assert store["stocks"]["RELIANCE"]["about"].startswith("Reliance makes")


# Real profiles from Screener (shortened), which the first rules read wrongly.
AUTO = "Automobile and Auto Components"


def test_a_part_maker_does_not_make_the_vehicles_it_names():
    assert classify("Apollo manufactures automatic bias and radial tyres, and tubes. The product profile includes prominent tyre "
                    "brands in the T&B, light truck, passenger car, and farm vehicle segments in India.",
                    "Market Position\nThe company holds a prominent position in the domestic truck and bus radial, and passenger car "
                    "radial tyre segments.", AUTO) == ["tyre"]
    assert classify("Exide Industries Ltd is primarily engaged in the manufacturing of storage batteries.",
                    "Products\nThe company manufactures batteries for 2W, 3W, 4W, E-rickshaws, UPS, and solar for trade and "
                    "aftermarket. In the infrastructure segment, it serves industries such as telecom, railways, power projects.",
                    AUTO) == ["battery"]
    # "Off-highway" is not a highway, and "for over 30 years" is not a customer list.
    assert classify("Balkrishna Industries started its Off-Highway tyre business in 1987. For over 30 years, BKT has focused on "
                    "specialist segments such as agricultural, construction and industrial applications.", "", AUTO) == ["tyre"]


def test_the_group_and_its_past_are_not_the_company():
    assert classify("Britannia's product portfolio includes Biscuits, Bread, Cakes, Rusk, and Dairy products.",
                    "Part of Wadia Group:\nBritannia belongs to the Wadia Group, which has presence in Airlines (Go Air), "
                    "Realty (Bombay Realty) and Textiles (Bombay Dyeing).", "Fast Moving Consumer Goods") == ["food", "dairy"]
    assert classify("JSW Steel is engaged in the manufacture and sale of Iron and Steel Products. It is the flagship business "
                    "of the JSW Group. The Group has interests in energy, infrastructure, cement, paints.", "", "Metals & Mining") == ["steel"]
    assert classify("Chambal Fertilisers is engaged in production of Urea. The company was also engaged in Software business, "
                    "However, in FY21, it sold the assets.", "", "Chemicals") == ["fertiliser"]
    assert classify("SBI Life started as a joint-venture between State Bank of India and BNP Paribas Cardif. It offers life "
                    "insurance.", "", FINANCIAL) == ["insurance"]


def test_names_and_customers_are_not_products():
    assert classify("Coal India Ltd is mainly engaged in mining and production of Coal. The major consumers of the company are "
                    "power and steel sectors. Consumers from other sectors include cement, fertilizers.", "",
                    "Metals & Mining") == ["mining"]
    assert "chemicals" not in classify("Its 2020 acquisition of Tata Chemicals' consumer business expanded its reach into "
                                       "staples like salt, pulses and spices.", "", "Fast Moving Consumer Goods")
    assert "bank" not in classify("Bajaj Finance is India's largest non-banking financial company (NBFC).", "", FINANCIAL)
    assert classify("TCS, led by its CEO, provides IT services.", "", IT) == ["software"]


def test_words_with_another_meaning_are_not_those_products():
    made = classify("Supreme offers Cross Laminated Films, Industrial Moulded Components, Moulded Furniture and Composite LPG "
                    "Cylinders, with injection moulded PVC fittings and pipes.", "", "Capital Goods")
    assert not {"media", "vaccine", "oil"} & set(made) and "pipes" in made
    assert "oil" not in classify("Bosch makes diesel and gasoline fuel injection systems and power tools.", "", AUTO)
    assert classify("Domino's is a leading player in the pizza segment. Popeyes offers fried chicken; Dunkin' sells coffee.", "",
                    "Consumer Services") == ["restaurant"]
