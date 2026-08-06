(ns kuramori.facts-test
  "The facts catalog is the governor's only source of jurisdictional
  truth, so its invariants are pinned here: an unknown jurisdiction must
  yield NOTHING (absence of a rule is not permission), every entry must
  carry a real citation, and coverage must be reported honestly rather
  than implied."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [kuramori.facts :as facts]))

(deftest unknown-jurisdiction-yields-nothing
  (testing "absence of a rule is not permission"
    (is (nil? (facts/jurisdiction "ATL")))
    (is (false? (facts/spec-basis-known? "ATL")))
    (is (nil? (facts/required-evidence "ATL")))
    (is (false? (facts/required-evidence-satisfied?
                 "ATL" #{:risk-assessment :protective-stop-verification
                         :operator-competency})))
    (testing "even an EMPTY checklist does not satisfy an unknown jurisdiction"
      (is (false? (facts/required-evidence-satisfied? "ATL" #{}))))))

(deftest every-entry-carries-a-real-citation
  (doseq [[iso3 entry] facts/catalog]
    (testing iso3
      (is (seq (:sources entry)) "an entry with no source is not a spec-basis")
      (is (every? #(str/starts-with? % "https://") (:sources entry)))
      (is (seq (:legal-basis entry)))
      (is (seq (:owner-authority entry)))
      (is (seq (:required-evidence entry))))))

(deftest evidence-is-all-or-nothing
  (let [iso3 "JPN"
        req (facts/required-evidence iso3)]
    (is (true? (facts/required-evidence-satisfied? iso3 req)))
    (testing "a superset still satisfies"
      (is (true? (facts/required-evidence-satisfied?
                  iso3 (conj (set req) :extra-thing)))))
    (testing "dropping any single required item fails"
      (doseq [k req]
        (is (false? (facts/required-evidence-satisfied? iso3 (disj (set req) k)))
            (str "dropping " k " must fail"))))))

(deftest coverage-is-reported-honestly
  (let [c (facts/coverage)]
    (is (= (count facts/catalog) (:count c)))
    (is (= (set (keys facts/catalog)) (set (:jurisdictions c))))
    (testing "the standards we could NOT verify a URL for are named, not hidden"
      (is (seq (:technical-standards-without-verified-url c)))
      (is (contains? (set (:technical-standards-without-verified-url c))
                     "ISO 3691-4")))))

(deftest handoff-well-formedness
  (let [ok {:id "h-1" :source-actor "kuramori" :batch-id "b-1" :sku-id "s-1"
            :quantity-units 12 :dispatched-at-iso "2026-08-06T00:00:00Z"}]
    (is (true? (facts/handoff-record-well-formed? ok)))
    (testing "every required field is actually required"
      (doseq [k (keys ok)]
        (is (false? (facts/handoff-record-well-formed? (dissoc ok k)))
            (str "removing " k " must be malformed"))))
    (testing "quantity must be a positive number"
      (is (false? (facts/handoff-record-well-formed? (assoc ok :quantity-units 0))))
      (is (false? (facts/handoff-record-well-formed? (assoc ok :quantity-units -1))))
      (is (false? (facts/handoff-record-well-formed? (assoc ok :quantity-units "12")))))
    (testing "a non-map is malformed"
      (is (false? (facts/handoff-record-well-formed? nil)))
      (is (false? (facts/handoff-record-well-formed? "h-1"))))))
