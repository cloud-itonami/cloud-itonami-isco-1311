(ns farm-management.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [farm-management.store :as store]
            [farm-management.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-site! st {:site-id "site-1" :name "Acme Farm" :verified? true})
    st))

(deftest ok-on-clean-harvest-schedule
  (let [st (fresh-store)
        proposal {:op :schedule-harvest :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:site-id "site-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-unregistered-site
  (let [st (fresh-store)
        proposal {:op :schedule-harvest :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:site-id "no-such-site"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-site (:rule %)) (:violations v)))))

(deftest hard-on-unverified-site
  (let [st (store/mem-store)
        _ (store/register-site! st {:site-id "site-2" :name "Unverified Farm" :verified? false})
        proposal {:op :schedule-harvest :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:site-id "site-2"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-site (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        proposal {:op :schedule-harvest :effect :direct-write :confidence 0.9 :stake :low}
        v (governor/check {:site-id "site-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-on-crop-anomaly-flag
  (let [st (fresh-store)
        proposal {:op :flag-crop-anomaly :effect :propose :confidence 0.9 :stake :high}
        v (governor/check {:site-id "site-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest escalates-on-high-cost-supply-order
  (let [st (fresh-store)
        proposal {:op :order-supplies :effect :propose :confidence 0.9 :stake :medium}
        v (governor/check {:site-id "site-1" :cost 7500} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest ok-on-low-cost-supply-order
  (let [st (fresh-store)
        proposal {:op :order-supplies :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:site-id "site-1" :cost 1000} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest escalates-on-low-confidence
  (let [st (fresh-store)
        proposal {:op :log-yield-report :effect :propose :confidence 0.2 :stake :low}
        v (governor/check {:site-id "site-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest store-records-and-ledger-append-only
  (let [st (fresh-store)]
    (store/commit-record! st {:site-id "site-1" :op :schedule-harvest})
    (store/append-ledger! st {:disposition :commit})
    (is (= 1 (count (store/records-of st "site-1"))))
    (is (= 1 (count (store/ledger st))))))
