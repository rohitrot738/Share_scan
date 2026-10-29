"""What a company makes or sells, read from the description Screener shows above its figures: the "About" paragraph
and the first of its "Key Points" (screener_page.parse keeps both). The app shows each product as a chip with its
icon (app/src/main/java/com/rohit/share_scan/Products.kt holds the same keys).

The text is matched against a fixed list of products, each with the English words that name it. Words that only say
who the products are for ("tyres for passenger cars, trucks and tractors", "serves clients in banking and
insurance") are left out, and so are sentences about the rest of the company's group or a business it has left, and
a Key Points section about its owners or history. The company's industry narrows what it can be: a bank's "car
loans" are loans, an IT company's "banking clients" are not a bank; and its main product rules some out: a tyre
maker's "truck and bus radial tyres" are not trucks. Standard library only, like the rest of the cache builder.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

MAX_PRODUCTS = 8
FINANCIAL = "Financial Services"
IT = "Information Technology"


@dataclass(frozen=True)
class Product:
    key: str
    group: str
    words: tuple[str, ...]


def _p(key: str, group: str, *words: str) -> Product:
    return Product(key, group, words)


# Order matters only where two products' words overlap: the first listed takes the words (see classify).
PRODUCTS: tuple[Product, ...] = (
    # Finance (only a financial company, or one whose industry is unknown, can have these)
    _p("bank", "finance", r"(?<!non-)(?<!non )\bbank(?:ing)?\b", r"\bbanker\b"),
    _p("loans", "finance", r"\bloans?\b", r"\blending\b", r"\bnbfcs?\b", r"housing finance", r"vehicle finance", r"micro ?finance",
       r"\bgold loans?\b", r"\bfinanc(?:e|ing) (?:company|services|solutions)\b", r"\bleasing\b"),
    _p("insurance", "finance", r"\binsurance\b", r"\binsurers?\b", r"\breinsurance\b"),
    _p("investing", "finance", r"\bbrok(?:ing|erage)\b", r"mutual funds?", r"asset management", r"wealth management", r"stock exchanges?",
       r"\bdepositor(?:y|ies)\b", r"portfolio management", r"investment banking", r"\bdemat\b", r"\bamcs?\b"),
    _p("payments", "finance", r"\bpayments?\b", r"credit cards?", r"\bwallets?\b", r"\bupi\b", r"\bfintech\b"),
    # Vehicles
    _p("car", "auto", r"passenger (?:cars?|vehicles?)", r"\bcars?\b(?! (?:loans?|finance|rentals?))", r"\bhatchbacks?\b", r"\bsedans?\b"),
    _p("suv", "auto", r"\bsuvs?\b", r"\butility vehicles?\b", r"\bmuvs?\b"),
    _p("bike", "auto", r"\btwo[- ]wheelers?\b", r"\b2[- ]?w(?:heelers?)?\b", r"\bmotorcycles?\b", r"\bscooters?\b", r"\bmopeds?\b", r"\bbikes?\b"),
    _p("auto3w", "auto", r"\bthree[- ]wheelers?\b", r"\b3[- ]?w(?:heelers?)?\b", r"\bauto[- ]?rickshaws?\b", r"\be-rickshaws?\b"),
    _p("truck", "auto", r"\btrucks?\b", r"commercial vehicles?", r"\b[lmish]cvs?\b", r"\btippers?\b", r"\bpick-?ups?\b"),
    _p("bus", "auto", r"\bbus(?:es)?\b"),
    _p("tractor", "auto", r"\btractors?\b", r"farm (?:equipment|machinery|mechani[sz]ation|implements)", r"\bharvesters?\b"),
    _p("ev", "auto", r"electric vehicles?", r"\bevs?\b", r"\bev charg\w*"),
    _p("tyre", "auto", r"\btyres?\b", r"\btires?\b"),
    _p("autoparts", "auto", r"auto(?:motive)? (?:components?|parts|ancillar\w+)", r"engine (?:components?|parts|valves)", r"\bbrakes?\b",
       r"\bclutch(?:es)?\b", r"gear ?box(?:es)?", r"\bpistons?\b", r"\baxles?\b", r"shock absorbers?", r"\bradiators?\b", r"\bseating systems?\b"),
    _p("battery", "auto", r"\bbatter(?:y|ies)\b", r"lithium[- ]ion"),
    _p("aircraft", "transport", r"\bairlines?\b", r"\baircraft\b", r"\baviation\b", r"\baerospace\b", r"\bairports?\b"),
    _p("ship", "transport", r"\bships?\b", r"shipbuilding", r"\bshipping\b", r"\bvessels?\b", r"\btankers?\b", r"\bdredg\w*"),
    _p("rail", "transport", r"\brailways?\b", r"\bwagons?\b", r"\blocomotives?\b", r"\bmetro (?:rail|coaches|projects?)\b", r"rolling stock"),
    _p("defence", "transport", r"\bdefen[cs]e\b", r"\bmissiles?\b", r"\bammunition\b", r"\bweapons?\b", r"\bradars?\b"),
    _p("logistics", "transport", r"\blogistics\b", r"\bcouriers?\b", r"\bwarehous\w*", r"\bfreight\b", r"\bcargo\b", r"supply chain services",
       r"express (?:delivery|parcels?)"),
    _p("port", "transport", r"\bports?\b(?! folio)", r"\bharbours?\b", r"\bjett(?:y|ies)\b"),
    # Health
    _p("api", "health", r"active pharmaceutical ingredients?", r"\bapis?\b", r"bulk drugs?", r"drug intermediates?"),
    _p("medicine", "health", r"\bpharmaceuticals?\b", r"\bformulations?\b", r"\bmedicines?\b", r"\bdrugs?\b", r"\bgenerics?\b",
       r"\btablets?\b", r"\bcapsules?\b", r"\bpharma\b"),
    _p("vaccine", "health", r"\bvaccines?\b", r"\binjectables?\b", r"\bbiologics?\b", r"\bbiosimilars?\b", r"\binsulin\b"),
    _p("hospital", "health", r"\bhospitals?\b", r"healthcare services", r"\bclinics?\b", r"\bivf\b"),
    _p("diagnostics", "health", r"\bdiagnostics?\b", r"\bpatholog\w*", r"\bradiology\b", r"diagnostic (?:centres?|centers?|labs?)"),
    _p("meddevice", "health", r"medical (?:devices?|equipment|consumables|disposables)", r"\bsyringes?\b", r"\bstents?\b", r"\bimplants?\b",
       r"\bsurgical\b", r"\bcatheters?\b"),
    _p("ayurveda", "health", r"\bayurved\w*", r"\bherbal\b", r"natural health", r"\bnutraceuticals?\b"),
    # Materials
    _p("cement", "materials", r"\bcement\b", r"\bclinker\b", r"ready[- ]mix(?:ed)? concrete"),
    _p("steel", "materials", r"\bsteel\b", r"sponge iron", r"pig iron", r"\btmt\b", r"\brebars?\b", r"\b[hc]r coils?\b",
       r"\bgalvani[sz]ed\b", r"\bstainless\b", r"ferro ?alloys?"),
    _p("metals", "materials", r"\balumin(?:i)?um\b", r"\bcopper\b", r"\bzinc\b", r"non[- ]ferrous", r"\bbrass\b"),
    _p("mining", "materials", r"\bmining\b", r"\bmines?\b", r"iron ore", r"\bcoal\b", r"\bbauxite\b", r"\bmanganese\b", r"\bminerals?\b"),
    _p("chemicals", "materials", r"\bchemicals?\b", r"\bdyes?\b", r"\bpigments?\b", r"\bintermediates?\b", r"\bsolvents?\b", r"caustic soda",
       r"chlor-?alkali", r"soda ash", r"\bfluorochemicals?\b"),
    _p("fertiliser", "materials", r"\bfertili[sz]ers?\b", r"\burea\b", r"\bdap\b", r"\bagrochemicals?\b", r"\bpesticides?\b", r"\binsecticides?\b",
       r"\bherbicides?\b", r"\bfungicides?\b", r"crop (?:protection|care|nutrition)", r"\bseeds?\b(?! (?:capital|funding|stage))"),
    _p("plastics", "materials", r"\bplastics?\b", r"\bpolymers?\b", r"\bpvc resins?\b", r"\bmasterbatch\w*", r"\bmoulded\b"),
    _p("pipes", "materials", r"\bpipes?\b", r"pipe fittings", r"\bcpvc\b", r"\bhdpe\b"),
    _p("paint", "materials", r"\bpaints?\b(?! chemicals)", r"\bcoatings?\b", r"\bvarnish\w*", r"\bputty\b"),
    _p("tiles", "materials", r"\btiles?\b", r"\bceramics?\b", r"sanitary ?ware", r"\bfaucets?\b", r"bath ?(?:fittings|ware)", r"\bvitrified\b",
       r"\bmarble\b", r"\bgranite\b", r"\bglass\b"),
    _p("paper", "materials", r"\bpaper(?:board)?s?\b", r"\btissues?\b", r"\bnewsprint\b", r"\bpulp\b"),
    _p("packaging", "materials", r"\bpackaging\b", r"\bcartons?\b", r"\bcorrugated\b", r"(?:bopp|bopet|pet|flexible|packaging) films?",
       r"\blaminated tubes?\b", r"\bcontainers?\b"),
    # Clothing and personal goods
    _p("textile", "clothing", r"\btextiles?\b", r"\byarns?\b", r"\bfabrics?\b", r"\bspinning\b", r"\bweaving\b", r"\bcotton\b", r"\bdenim\b",
       r"\bviscose\b", r"(?:polyester|staple|synthetic) fib(?:re|er)s?"),
    _p("apparel", "clothing", r"\bgarments?\b", r"\bapparels?\b", r"\bclothing\b", r"\bshirts?\b", r"\binnerwear\b", r"\bsarees?\b",
       r"ethnic wear", r"\bfashion\b"),
    _p("footwear", "clothing", r"\bfootwear\b", r"\bshoes?\b", r"\bsandals?\b", r"\bslippers?\b", r"\bsneakers?\b"),
    _p("jewellery", "clothing", r"\bjewell?e?ry\b", r"\bdiamonds?\b", r"(?:gold|silver) (?:ornaments|coins|bars|articles)", r"\bornaments?\b",
       r"\bbullion\b", r"\bgemstones?\b"),
    _p("watch", "clothing", r"\bwatches\b", r"\beyewear\b", r"\bspectacles\b", r"\bsunglasses\b"),
    _p("bags", "clothing", r"\bluggage\b", r"(?:hand|travel|school) ?bags?", r"\bsuitcases?\b", r"leather (?:goods|products|bags)"),
    # Electrical, electronics and technology
    _p("cable", "electrical", r"\bcables?\b(?! protection| trays?)", r"\bwires?\b(?! rods?)", r"\bwiring\b", r"\bconductors?\b"),
    _p("light", "electrical", r"\blighting\b", r"\bleds\b", r"\bled (?:lights?|lamps?|bulbs?|panels?|tvs?|displays?|luminaires?)",
       r"\blamps?\b", r"\bbulbs?\b", r"\bluminaires?\b"),
    _p("appliance", "electrical", r"\bfans?\b", r"air[- ]?condition\w*", r"\brefrigerat\w*", r"washing machines?",
       r"(?:home|kitchen|consumer|electrical|domestic) appliances", r"water heaters?", r"\bgeysers?\b", r"\bair coolers?\b",
       r"mixer grinders?"),
    _p("electrical", "electrical", r"\btransformers?\b", r"\bswitchgears?\b", r"electric(?:al)? motors?", r"induction motors?", r"circuit breakers?",
       r"\benergy meters?\b", r"\binverters?\b", r"\bgensets?\b", r"\bgenerators?\b"),
    _p("electronics", "electrical", r"\belectronics?\b", r"\bems\b", r"\bpcbs?\b", r"printed circuit", r"\btelevisions?\b", r"set[- ]top boxes?",
       r"\bsemiconductors?\b"),
    _p("telecom", "tech", r"\btelecom\w*", r"mobile (?:services|networks?|phones?|handsets?)", r"\bbroadband\b", r"\b[45]g\b",
       r"(?:optical|optic) fib(?:re|er)s?", r"fib(?:re|er) optic\w*", r"(?:telecom|mobile) towers?", r"\bsmartphones?\b", r"\bhandsets?\b"),
    _p("software", "tech", r"\bsoftware\b", r"\bit services\b", r"information technology", r"\bsaas\b", r"\bcloud\b",
       r"digital (?:services|transformation|solutions)", r"\bbpo\b", r"\bbpm\b", r"\boutsourcing\b", r"data cent(?:re|er)s?"),
    _p("internet", "tech", r"\be-?commerce\b", r"online (?:platforms?|marketplaces?|portals?|travel|services)", r"\binternet\b(?! of things)",
       r"\bmobile apps?\b", r"(?:digital|internet|technology) platforms?",
       r"\bmarketplace\b"),
    _p("media", "media", r"\b(?:feature|hindi|regional) films?\b", r"\bfilm (?:production|distribution|studios?|exhibition)", r"motion pictures?",
       r"\bmovies?\b", r"television (?:channels?|broadcast\w*|network)", r"\btv channels?\b", r"\bbroadcast\w*",
       r"\bentertainment\b", r"\bott\b", r"\bmusic\b", r"\bcinemas?\b", r"\bmultiplex\w*"),
    _p("news", "media", r"\bnewspapers?\b", r"\bpublish\w*", r"\bmagazines?\b", r"print media"),
    _p("gaming", "media", r"\bgaming\b", r"online games", r"\blotter(?:y|ies)\b", r"\bcasinos?\b"),
    _p("toys", "media", r"\btoys?\b", r"sports goods", r"\bpuzzles\b", r"\bstationery\b", r"\bpens\b", r"\bpencils?\b"),
    # Energy
    _p("edibleoil", "food", r"edible oils?", r"cooking oils?", r"vegetable oils?", r"palm oil", r"mustard oil", r"soya(?:bean)? oil",
       r"sunflower oil", r"refined oils?", r"\boilseeds?\b", r"soya meal", r"de-oiled cakes?"),
    _p("oil", "energy", r"(?<!edible )(?<!cooking )(?<!vegetable )(?<!palm )(?<!mustard )(?<!soybean )(?<!soya )(?<!sunflower )"
                        r"(?<!refined )(?<!essential )(?<!hair )(?<!castor )(?<!coconut )\boil\b(?! ?seeds?| cakes?)",
       r"\bcrude\b", r"\bpetroleum\b", r"\brefin(?:ery|eries)\b", r"oil refining", r"\blubricants?\b",
       r"\bfuels?\b(?! (?:injection|systems?|cells?|efficien\w*))",
       r"\bpetrochemicals?\b", r"\blpg\b(?! cylinders?)", r"\bbitumen\b", r"hydrocarbon"),
    _p("gas", "energy", r"natural gas", r"\bcng\b", r"\bpng\b", r"city gas", r"\blng\b", r"gas distribution", r"industrial gases"),
    _p("power", "energy", r"power (?:generation|plants?|distribution|transmission|projects?|utility|producer)(?! equipment)", r"\belectricity\b",
       r"thermal power", r"\bhydro(?:electric|power)?\b", r"transmission lines?"),
    _p("solar", "energy", r"\bsolar\b", r"wind (?:power|energy|turbines?|farms?)", r"\brenewables?\b", r"green (?:energy|hydrogen)",
       r"\bphotovoltaic\b"),
    # Food, drink and household
    _p("household", "home", r"\bfmcg\b", r"fast[- ]moving consumer goods", r"consumer goods", r"household (?:products|goods)", r"home ?care"),
    _p("personalcare", "home", r"\bsoaps?\b", r"\bdetergents?\b", r"\bshampoos?\b", r"personal care", r"\btoothpaste\b", r"oral care",
       r"hair (?:oil|care)", r"skin ?care", r"\bcosmetics?\b", r"\bbeauty\b", r"\bdeodorants?\b", r"\bperfumes?\b", r"\bfragrances?\b",
       r"\bhygiene\b", r"\bdiapers?\b", r"sanitary (?:napkins?|pads?)"),
    _p("food", "food", r"\bbiscuits?\b", r"\bsnacks?\b", r"\bnoodles?\b", r"packaged foods?", r"food products?", r"processed foods?",
       r"ready[- ]to[- ](?:eat|cook)", r"\bconfectioner\w*", r"\bchocolates?\b", r"\bbakery\b", r"\bbreads?\b", r"\bpasta\b", r"\bketchup\b",
       r"\bsauces?\b", r"\bnamkeen\b", r"breakfast cereals?", r"\bfood (?:segment|business|compan(?:y|ies)|brands?)\b"),
    _p("dairy", "food", r"\bdairy\b", r"\bmilk\b", r"ice ?creams?", r"\bghee\b", r"\bbutter\b", r"\bcheese\b", r"\bpaneer\b", r"\bcurd\b",
       r"\byog(?:h)?urt\b"),
    _p("tea", "food", r"\btea\b", r"\bcoffee\b", r"\bplantations?\b"),
    _p("sugar", "food", r"\bsugar\b", r"\bethanol\b", r"\bdistiller(?:y|ies)\b", r"\bmolasses\b", r"\bjaggery\b"),
    _p("grains", "food", r"\brice\b", r"\bbasmati\b", r"\bwheat\b", r"\bflour\b", r"\batta\b", r"\bpulses\b", r"\bgrains?\b",
       r"agri(?:cultural)? (?:products|commodities)", r"\bagri-?business\b"),
    _p("beverage", "food", r"\bbeverages?\b(?! cans?)", r"soft drinks?", r"\bjuices?\b", r"packaged (?:drinking )?water", r"bottled water", r"energy drinks?"),
    _p("alcohol", "food", r"\bbeer\b", r"\bliquor\b", r"\bwhisk(?:e)?y\b", r"\bspirits\b", r"\balcoholic\b", r"\bwines?\b", r"\bbrewer(?:y|ies)\b",
       r"\bvodka\b", r"\bbrandy\b", r"\brum\b"),
    _p("tobacco", "food", r"\bcigarettes?\b", r"\btobacco\b", r"\bbidis?\b", r"\bcigars?\b"),
    _p("spices", "food", r"\bspices?\b", r"\bmasalas?\b", r"\bchill(?:i|y|ies)\b", r"\bturmeric\b", r"\bseasonings?\b"),
    _p("meat", "food", r"\bpoultry\b", r"\bchicken\b", r"\beggs?\b", r"\bmeat\b", r"\bseafood\b", r"\bshrimps?\b", r"\bprawns?\b", r"\bfish\b",
       r"\baquacultur\w*", r"\bbroilers?\b"),
    _p("animalfeed", "food", r"(?:animal|cattle|poultry|aqua|fish|shrimp) feeds?", r"\bveterinary\b"),
    _p("restaurant", "services", r"\brestaurants?\b", r"quick service", r"\bqsrs?\b", r"\bcafes?\b", r"\bpizzas?\b", r"\bburgers?\b",
       r"food (?:service|delivery)"),
    _p("hotel", "services", r"\bhotels?\b", r"\bresorts?\b", r"\bhospitality\b", r"\btourism\b", r"\btravel\b", r"\bholidays?\b"),
    _p("retail", "services", r"retail (?:stores?|chains?|outlets?|business)", r"\bsupermarkets?\b", r"\bhypermarkets?\b", r"departmental stores?",
       r"value retail", r"\bretailers?\b", r"\bretailing\b", r"\bretail(?= (?:business|segment|arm|venture|formats?|network))"),
    _p("furniture", "home", r"\bfurniture\b", r"\bmattress(?:es)?\b", r"\bplywood\b", r"\blaminates?\b", r"\bmdf\b", r"\bveneers?\b",
       r"\bfurnishings?\b", r"home d[eé]cor", r"modular kitchens?"),
    _p("cookware", "home", r"\bcookware\b", r"\bkitchenware\b", r"pressure cookers?", r"\butensils\b"),
    # Industry and building
    _p("machinery", "industry", r"\bmachinery\b", r"\bmachines?\b", r"capital goods", r"engineering (?:products|goods|services)", r"\bpumps?\b",
       r"\bvalves?\b", r"\bcompressors?\b", r"\bbearings?\b", r"\bboilers?\b", r"\bturbines?\b", r"\bcranes?\b", r"\bforgings?\b",
       r"\bcastings?\b", r"heavy equipment", r"industrial equipment"),
    _p("construction", "industry", r"\bconstruction\b(?! (?:and \w+ )?(?:chemicals|equipment))", r"\bepc\b",
       r"\binfrastructure (?:projects?|development|developers?|company|construction|works)", r"\broads?\b", r"(?<!off-)\bhighways?\b", r"\bbridges?\b",
       r"\btunnels?\b", r"\bdams?\b", r"\birrigation\b"),
    _p("realty", "industry", r"real estate", r"residential (?:projects?|apartments?|townships?)", r"\bapartments?\b", r"housing projects?",
       r"property develop\w*", r"commercial (?:spaces?|offices?|real estate)", r"\btownships?\b", r"\bvillas?\b", r"\bplotted\b"),
    _p("water", "industry", r"water (?:treatment|supply|purif\w*|management|solutions)", r"\bdesalination\b", r"waste ?water", r"\bsewage\b"),
    _p("recycling", "industry", r"\brecycl\w*", r"waste management", r"\bscrap\b", r"\be-?waste\b"),
    # Services
    _p("education", "services", r"\beducation\w*", r"\bschools?\b", r"\bcoaching\b", r"\bedtech\b", r"\buniversit(?:y|ies)\b", r"\bcolleges?\b"),
    _p("staffing", "services", r"\bstaffing\b", r"\bmanpower\b", r"\brecruitment\b", r"facility management", r"security services",
       r"\bhousekeeping\b"),
)

KEYS = tuple(p.key for p in PRODUCTS)

# What an industry's companies can make, by group or by product; an industry not named here allows every group but
# finance, and an unknown industry every group.
SECTOR_GROUPS = {
    FINANCIAL: {"finance"},
    IT: {"tech", "media", "electrical", "services", "payments"},
}
_COMPILED = [(p, re.compile("|".join(p.words))) for p in PRODUCTS]

# Words after which the rest of a sentence names who the products are for, not what they are.
_FOR_WHOM = re.compile(
    r"\b(?:for|used (?:in|by|for)|caters? to|catering to|serv(?:es|ing|ed)|clients?|customers?|end[- ]users?|"
    r"applications? (?:in|across)|industries such as|sectors? (?:such as|like)|suppl(?:y|ies|ied|ying|ier|iers) (?:to|for)|"
    r"segments? (?:such as|like|including)|market segments?|verticals?|user industries|such industries|"
    r"consumers (?:of|from|include|are)|major consumers)\b")
# "For" that does not start a list of customers: "known for its biscuits", "over 40 years".
_FOR_KEEP = re.compile(r"(?:known|famous|popular|renowned|recogni[sz]ed|reputed|leader|responsible|licen[cs]ed?|approval|approved|"
                       r"orders?|demand|accounts?|accounting|preferred|used)\s+$")


# "For" that says how long: "for over 30 years", "for the last decade".
_FOR_TIME = re.compile(r"for (?:over |more than |nearly |almost |about |around )?(?:the (?:last|past) |several |many |\d+\+? )"
                       r"(?:\w+ )?(?:years?|decades?|months?|generations?)\b")

# Sentences about the rest of the company's group, or a business it has left: what they name is not its own product.
_NOT_OWN = re.compile(r"\bgroup compan(?:y|ies)\b|\b(?:other )?business(?:es)? of the group\b|\bthe group (?:has|have|operates|comprises|"
                      r"includes|is present|also)\b|\bgroup which operates\b|\bwas (?:also )?engaged in\b|\b(?:divest\w*|discontinued|"
                      r"exited|hived off)\b|\bcease the\b|\bjoint[- ]venture (?:between|with)\b")

# A first Key Points section on these is about the company's owners or past, not its products: "Part of Wadia Group".
_OTHER_SECTION = re.compile(r"\bgroup\b|history|architecture|parentage|shareholding|promoter|management|awards?\b|ratna\b|"
                            r"subsidiar|joint venture|merger|acquisition", re.IGNORECASE)

# What a company whose main product is the key does not make, though its text names them: a tyre maker's "truck and
# bus radial tyres" and a battery maker's "batteries for 2W, 3W" are not vehicles, a pizza chain's chicken and coffee
# and a cinema's food and drinks are on its menu.
_VEHICLES = frozenset({"car", "suv", "bike", "auto3w", "truck", "bus", "tractor", "ev"})
_NOT_WITH_MAIN = {
    "tyre": _VEHICLES, "battery": _VEHICLES, "autoparts": _VEHICLES,
    "restaurant": frozenset({"meat", "tea", "dairy", "food", "beverage", "grains"}),
    "media": frozenset({"food", "beverage"}),
}

# Words that end a customer list and go back to what the company makes: "..., and also paints".
_FOR_END = re.compile(r",? (?:and )?(?:it |the company )?also\b(?! (?:to|for|by|in)\b)")


def _for_whom_spans(text: str) -> list[tuple[int, int]]:
    """Each sentence's part from a customer word to the sentence's end, or to where it goes back to its products."""
    spans = []
    for sentence in re.finditer(r"[^.;:!?\n]+", text):
        s, at = sentence.group(0), 0
        while (m := _FOR_WHOM.search(s, at)):
            if m.group(0) == "for" and (_FOR_KEEP.search(s[:m.start()]) or _FOR_TIME.match(s, m.start())):
                at = m.end()
                continue
            end = _FOR_END.search(s, m.end())
            spans.append((sentence.start() + m.start(), sentence.start() + (end.start() if end else len(s))))
            if not end:
                break
            at = end.end()
    return spans


def _not_own_spans(text: str) -> list[tuple[int, int]]:
    """The sentences about the group's other businesses or a business the company has left."""
    return [(m.start(), m.end()) for m in re.finditer(r"[^.;:!?]+", text) if _NOT_OWN.search(m.group(0))]


def _own_points(points: str) -> str:
    """The Key Points text, unless its section is about something other than the company's products."""
    heading = (points or "").strip().split("\n", 1)[0]
    return "" if _OTHER_SECTION.search(heading) else points


def _normal(text: str) -> str:
    text = (text or "").lower().replace("&", " and ").replace("’", "'").replace("–", "-").replace("—", " - ")
    return re.sub(r"\s+", " ", text)


def classify(about: str, points: str = "", sector: str = "") -> list[str]:
    """The products the company's description names, the earliest named first, at most MAX_PRODUCTS; a word inside
    a customer list does not count. `sector` is its NSE industry ("" when unknown)."""
    text = _normal(about) + " . " + _normal(_own_points(points))
    hidden = _for_whom_spans(text) + _not_own_spans(text)
    allowed = SECTOR_GROUPS.get(sector)
    found: list[tuple[int, str]] = []
    taken: list[tuple[int, int]] = []
    for product, pattern in _COMPILED:
        if allowed is not None and product.group not in allowed and product.key not in allowed:
            continue
        if allowed is None and sector and product.group == "finance":
            continue  # a maker's "bank guarantees" or "payments" are not what it sells
        # Every naming of the product is its own, so a later product cannot read "washing machines" as machinery.
        # A plural followed by an apostrophe is a name: "Tata Chemicals' consumer business".
        spans = [(m.start(), m.end()) for m in pattern.finditer(text)
                 if not any(a <= m.start() < b for a, b in hidden) and not any(a < m.end() and m.start() < b for a, b in taken)
                 and not (text[m.end() - 1] == "s" and text[m.end():m.end() + 1] == "'")]
        if spans:
            found.append((spans[0][0], product.key))
            taken.extend(spans)
    found.sort()
    made = [key for _, key in found]
    if made:
        made = [key for key in made if key not in _NOT_WITH_MAIN.get(made[0], ())]
    return made[:MAX_PRODUCTS]
