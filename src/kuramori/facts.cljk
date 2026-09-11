(ns kuramori.facts
  "Per-jurisdiction warehouse-mobile-robot regulatory catalog -- the
  spec-basis table `kuramori.governor` checks every proposal against
  ('did the advisor cite an OFFICIAL public source for this
  jurisdiction's driverless-truck / mobile-robot / powered-industrial-
  truck safety regime, or did it invent one?').

  Each entry is a REAL jurisdiction with a REAL instrument governing
  self-propelled materials-handling equipment sharing a floor with
  workers: Japan's 労働安全衛生法 and its 労働安全衛生規則 (the
  安全衛生 regime the 厚生労働省 enforces, with JIS the domestic
  standards body); the United States' OSHA powered-industrial-truck
  standard 29 CFR 1910.178 plus OSHA's and NIOSH's robotics safety
  programmes; the EU Machinery Regulation (EU) 2023/1230, which replaced
  the Machinery Directive and explicitly covers autonomous mobile
  machinery; Great Britain's Provision and Use of Work Equipment
  Regulations 1998 (PUWER), enforced by HSE; Canada's Occupational
  Health and Safety Regulations (SOR/86-304) under the Canada Labour
  Code; the Republic of Korea's Occupational Safety and Health Act; and
  Australia's model Work Health and Safety Regulations 2011.

  HONESTY NOTE ON THE TECHNICAL STANDARDS. The two standards that
  actually specify the engineering requirements for this equipment class
  are ISO 3691-4 (driverless industrial trucks and their systems) and
  ANSI/A3 R15.08 (industrial mobile robots). They are named in
  `:technical-standards` below as prose, and deliberately carry NO url:
  iso.org and automate.org both refuse automated retrieval (HTTP 403),
  so no URL for them was verifiable at catalog time and none is
  asserted. Every url that IS present in this file was fetched and
  returned 2xx on 2026-08-06; `:provenance` records that and nothing
  stronger -- reachability of the publisher's page, NOT an extraction of
  the instrument's full text, and NOT a claim about any specific clause
  number.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.")

(def catalog
  "iso3 -> requirement map.

  `:required-evidence` is the warehouse-mobile-robot evidence set every
  jurisdiction below demands in some form before self-propelled
  equipment may share a floor with workers: a documented risk
  assessment for the equipment, a commissioned protective-stop /
  emergency-stop verification, and an operator/maintainer competency
  record. `:legal-basis` / `:owner-authority` / `:sources` are the
  citation `kuramori.governor` requires before any proposal can leave
  HOLD."
  {"JPN"
   {:name "JPN"
    :owner-authority "厚生労働省 (労働基準局 安全衛生部) / 日本産業標準調査会 (JISC)"
    :legal-basis "労働安全衛生法 (昭和47年法律第57号) および 労働安全衛生規則 (昭和47年労働省令第32号)"
    :technical-standards ["ISO 3691-4 (無人搬送車系 — 引用のみ、URL未検証)"
                          "JIS B 8433 系 産業用ロボット安全 (JISC 経由)"]
    :required-evidence #{:risk-assessment :protective-stop-verification :operator-competency}
    :sources ["https://elaws.e-gov.go.jp/document?lawid=347AC0000000057"
              "https://elaws.e-gov.go.jp/document?lawid=347M50002000032"
              "https://www.mhlw.go.jp/stf/seisakunitsuite/bunya/koyou_roudou/roudoukijun/anzen/index.html"
              "https://www.jisc.go.jp/"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}

   "USA"
   {:name "USA"
    :owner-authority "Occupational Safety and Health Administration (OSHA) / NIOSH / NIST"
    :legal-basis "29 CFR 1910.178 Powered industrial trucks"
    :technical-standards ["ANSI/A3 R15.08 Industrial Mobile Robots (引用のみ、URL未検証)"
                          "ISO 3691-4 (引用のみ、URL未検証)"]
    :required-evidence #{:risk-assessment :protective-stop-verification :operator-competency}
    :sources ["https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.178"
              "https://www.osha.gov/robotics"
              "https://www.cdc.gov/niosh/robotics/about/index.html"
              "https://www.nist.gov/programs-projects/measurement-science-manufacturing-robotics"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 規則本文の抽出は行っていない。"}

   "EUR"
   {:name "EUR"
    :owner-authority "European Commission (Internal Market) — Machinery Regulation"
    :legal-basis "Regulation (EU) 2023/1230 on machinery (repealing Directive 2006/42/EC)"
    :technical-standards ["ISO 3691-4 (harmonised standard candidate, 引用のみ、URL未検証)"]
    :required-evidence #{:risk-assessment :protective-stop-verification :operator-competency}
    :sources ["https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32023R1230"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}

   "GBR"
   {:name "GBR"
    :owner-authority "Health and Safety Executive (HSE)"
    :legal-basis "Provision and Use of Work Equipment Regulations 1998 (SI 1998/2306, PUWER)"
    :technical-standards ["ISO 3691-4 (引用のみ、URL未検証)"]
    :required-evidence #{:risk-assessment :protective-stop-verification :operator-competency}
    :sources ["https://www.legislation.gov.uk/uksi/1998/2306/contents/made"
              "https://www.hse.gov.uk/work-equipment-machinery/puwer.htm"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}

   "CAN"
   {:name "CAN"
    :owner-authority "Employment and Social Development Canada (Labour Program)"
    :legal-basis "Canada Occupational Health and Safety Regulations (SOR/86-304) under the Canada Labour Code"
    :technical-standards ["CSA / ISO 3691-4 系 (引用のみ、URL未検証)"]
    :required-evidence #{:risk-assessment :protective-stop-verification :operator-competency}
    :sources ["https://laws-lois.justice.gc.ca/eng/regulations/SOR-86-304/index.html"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}

   "KOR"
   {:name "KOR"
    :owner-authority "고용노동부 Ministry of Employment and Labor"
    :legal-basis "산업안전보건법 Occupational Safety and Health Act"
    :technical-standards ["KS / ISO 3691-4 系 (引用のみ、URL未検証)"]
    :required-evidence #{:risk-assessment :protective-stop-verification :operator-competency}
    :sources ["https://www.law.go.kr/LSW/eng/engLsSc.do?menuId=2&section=lawNm&query=OCCUPATIONAL+SAFETY+AND+HEALTH+ACT"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}

   "AUS"
   {:name "AUS"
    :owner-authority "Safe Work Australia (model WHS laws) / Commonwealth"
    :legal-basis "Work Health and Safety Regulations 2011 (F2011L01849)"
    :technical-standards ["AS / ISO 3691-4 系 (引用のみ、URL未検証)"]
    :required-evidence #{:risk-assessment :protective-stop-verification :operator-competency}
    :sources ["https://www.legislation.gov.au/F2011L01849/latest/text"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}})

(defn jurisdiction
  "The catalog entry for `iso3`, or nil. nil means NO spec-basis exists
  for that jurisdiction -- the advisor must not invent one."
  [iso3]
  (get catalog iso3))

(defn spec-basis-known?
  "True iff this jurisdiction has an official spec-basis on file."
  [iso3]
  (some? (jurisdiction iso3)))

(defn required-evidence
  "The evidence set this jurisdiction demands, or nil when unknown."
  [iso3]
  (:required-evidence (jurisdiction iso3)))

(defn required-evidence-satisfied?
  "True iff `checklist` (a set of satisfied evidence keys) covers every
  item this jurisdiction requires. An UNKNOWN jurisdiction is never
  satisfied -- absence of a rule is not permission."
  [iso3 checklist]
  (let [req (required-evidence iso3)]
    (boolean (and req (every? (set checklist) req)))))

(defn coverage
  "Honest coverage report: which jurisdictions this catalog actually
  covers, and the count. Anything outside `:jurisdictions` has NO
  spec-basis."
  []
  {:jurisdictions (vec (sort (keys catalog)))
   :count (count catalog)
   :evidence-keys #{:risk-assessment :protective-stop-verification :operator-competency}
   :technical-standards-without-verified-url
   ["ISO 3691-4" "ANSI/A3 R15.08"]
   :note (str "ISO 3691-4 / ANSI/A3 R15.08 は本カタログの対象機器を実際に規定する技術標準だが、"
              "iso.org / automate.org は自動取得を 403 で拒否するため URL を主張しない。"
              "掲載 URL は 2026-08-06 に 2xx を確認した publisher ページであり、条文全文の抽出ではない。")})

;; ---------------------------------------------------------------- handoff

(defn handoff-record-well-formed?
  "A cross-actor handoff record (kuramori -> a downstream actor such as
  cloud-itonami-isic-5210 storage or a last-mile sibling) is well-formed
  iff it carries every required field and a positive quantity. Absence of
  a handoff is never itself a violation -- only a present-but-malformed
  one is."
  [handoff]
  (boolean
   (and (map? handoff)
        (every? #(some? (get handoff %))
                [:id :source-actor :batch-id :sku-id :quantity-units :dispatched-at-iso])
        (number? (:quantity-units handoff))
        (pos? (:quantity-units handoff)))))
