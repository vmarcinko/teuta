(ns vmarcinko.teuta-test
  (:require [midje.sweet :refer :all]
            [vmarcinko.teuta :refer :all]))

(fact "Empty container for empty specification"
  (create-container {}) => {})

(defrecord LeafComp [state-atom]
  Lifecycle
    (start [this]
      (swap! state-atom #(+ % 2)))
    (stop [this]
      (swap! state-atom dec)))

(defrecord Comp1 [comp2])

(defrecord Comp2 [leaf-comp])

;; we define the spec as function because it contains mutable data (atom), so we
;; don't want to use it during mutliple tests
(defn spec-1
  []
  {:my-comp-1 [map->Comp1 {:comp2 (comp-ref :my-comp-2)}]
   :my-comp-2 [map->Comp2 {:leaf-comp (comp-ref :my-leaf-comp)}]
   :my-leaf-comp [map->LeafComp {:state-atom (atom 0)}]})

(fact "Component injection check with keyword IDs"
  (let [container (create-container (spec-1))]
    (vec (keys container)) => [:my-leaf-comp :my-comp-2 :my-comp-1]
    (:comp2 (container :my-comp-1)) => (container :my-comp-2)
    (:leaf-comp (container :my-comp-2)) => (container :my-leaf-comp)))

(fact "Successful container lifecycle check"
  (let [container (create-container (spec-1))]
    (start-container container)
    (deref (:state-atom (container :my-leaf-comp))) => 2
    (stop-container container)
    (deref (:state-atom (container :my-leaf-comp))) => 1))

(defrecord Comp3 [comp1]
  Lifecycle
    (start [this]
      (throw (IllegalArgumentException. "Some error")))
    (stop [this]
      nil))

(def spec-1-addon {:my-comp-3 [map->Comp3 {:comp1 (comp-ref :my-comp-1)}]})

(fact "Failed container lifecycle check"
  (let [container (create-container (merge (spec-1) spec-1-addon))]
    (vec (keys container)) => [:my-leaf-comp :my-comp-2 :my-comp-1 :my-comp-3]
    (start-container container) => (throws IllegalStateException)
    (deref (:state-atom (container :my-leaf-comp))) => 1))

(def spec-2 {"my-comp-1" [hash-map :comp2 (comp-ref "my-comp-2")]
             "my-comp-2" [hash-map :leaf-comp (comp-ref "my-leaf-comp")]
             "my-leaf-comp" [hash-map :state-atom (atom 0)]})

(fact "Component injection check with string IDs"
  (let [container (create-container spec-2)]
    (vec (keys container)) => ["my-leaf-comp" "my-comp-2" "my-comp-1"]
    (:comp2 (container "my-comp-1")) => (container "my-comp-2")
    (:leaf-comp (container "my-comp-2")) => (container "my-leaf-comp")))

(def spec-3 {"my-comp-1" [hash-map :comp2 (comp-ref "my-comp-2")]
             "my-comp-2" [hash-map :leaf-comp (comp-ref "my-leaf-comp")]
             "my-leaf-comp" [hash-map :comp1 (comp-ref "my-comp-1")]})

(fact "Circular dependencies error check"
  (create-container spec-3) => (throws IllegalArgumentException))

(def spec-4 {"my-comp-1" [hash-map :comp2 (comp-ref "my-comp-2")]
             "my-comp-2" [hash-map :leaf-comp (comp-ref "my-leaf-comp")]
             "my-leaf-comp" [hash-map :comp1 (comp-ref "my-comp-3")]})

(fact "Unknown dependency error check"
  (create-container spec-4) => (throws IllegalArgumentException))

(def spec-5 {:my-comp-1 [hash-map :db-url (param-ref :db :url) :db-username (param-ref :db :username)]})

(fact "Parametrizing component construction"
  (let [container (create-container spec-5
                    {:db {:url "jdbc:url:someUrl"
                          :username "johndoe"}})]
    (:db-url (container :my-comp-1)) => "jdbc:url:someUrl"
    (:db-username (container :my-comp-1)) => "johndoe"))

(fact "Non-existing parameter error check"
  (create-container spec-5) => (throws IllegalArgumentException))
