(ns farm-management.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [farm-management.actor :as actor]
            [farm-management.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-site! st {:site-id "site-1" :name "Acme Farm" :verified? true})
    st))

(deftest commits-a-clean-low-risk-request
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:site-id "site-1" :op :schedule-harvest :stake :low}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "site-1"))))))

(deftest holds-on-unregistered-site-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:site-id "no-such-site" :op :schedule-harvest :stake :low}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "no-such-site")))
    (is (= :hold (:disposition (:state result))))))

(deftest interrupts-then-commits-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; crop anomaly flag always escalates (governor invariant)
        request {:site-id "site-1" :op :flag-crop-anomaly :stake :high}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "site-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (some? (get-in resumed [:state :record])))
      (is (= 1 (count (store/records-of st "site-1")))))))

(deftest interrupts-high-cost-supply-order
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:site-id "site-1" :op :order-supplies :stake :medium :cost 10000}
        interrupted (actor/run-request! graph request {} "thread-4")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "site-1")))
    (let [resumed (actor/approve! graph "thread-4")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "site-1")))))))
