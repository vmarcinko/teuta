(ns vmarcinko.teuta
  "Provides core dependency injection (DI) container functonality: creating component container,
  starting and stopping it. Components that have a lifecycle should satisfy Lifecycle protocol."
  (:require [vmarcinko.teuta.topo-sort :as topo-sort]
            [vmarcinko.teuta.walk :as walk]
            [clojure.tools.logging :as log]))

(defn- component-ref? [x]
  (and
    (vector? x)
    (= (first x) ::comp-ref)))

(defn- get-component-ref-id [x]
  (nth x 1))

(defn comp-ref
  "Creates reference to component identified by supplied comp-id.
  Returns vector [::comp-ref comp-id]."
  [comp-id]
  [::comp-ref comp-id])

(defn- parameter-ref? [x]
  (and
    (vector? x)
    (= (first x) ::param-ref)))

(defn- get-parameter-ref-id-path [x]
  (rest x))

(defn param-ref
  "Creates reference to parameter identified by supplied param-id-path.
  Returns vector [::param-ref & param-id-path]."
  [& param-id-path]
  {:pre [(seq param-id-path)]}
  (into [::param-ref] param-id-path))

(defn- collect-component-ref-ids
  "Walk the tree of component factory args, and collect all referenced component IDs"
  [[_ & factory-args]]
  (let [component-refs (filter component-ref? (tree-seq coll? seq factory-args))]
    (set (map #(nth % 1) component-refs))))

(defn- update-map-vals [m f]
  (zipmap (keys m) (map f (vals m))))

(defn- topo-sort-component-ids [container-spec]
  "Returns vector of component IDs sorted topologically, starting from components without dependencies."
  (let [dependency-map (update-map-vals container-spec collect-component-ref-ids)]
    (vec (topo-sort/topo-sort-deps dependency-map))))

(defmethod print-method ::referenced-component
  [x ^java.io.Writer writer]
  (let [component-id (::component-id (meta x))]
    (.write writer (str "<<component " component-id ">>"))))

(defn- create-replace-factory-args-fn [parameters container]
  (fn [x]
    (cond
      (component-ref? x) (let [component-id (get-component-ref-id x)]
                           (if-let [component (get container component-id)]
                             (if (instance? clojure.lang.IObj x)
                               ; store originally referenced component-id value as meta-data to be used in
                               ; polymorphic print-method which is dispatched based on :type value
                               (with-meta component {:type ::referenced-component
                                                     ::component-id component-id})
                               component)
                             (throw (IllegalArgumentException.
                                      (str "Invalid component reference - no component specified under ID '" component-id "'")))))
      (parameter-ref? x) (let [parameter-id-path (get-parameter-ref-id-path x)]
                           (if-let [parameter-value (get-in parameters parameter-id-path)]
                             parameter-value
                             (throw (IllegalArgumentException.
                                      (str "Invalid parameter reference - no parameter provided with ID path '" parameter-id-path "'")))))
      :else x)))

(defn- create-component [parameters container container-spec-entry]
  (let [[component-id [factory-fn & factory-args]] container-spec-entry
        replace-factory-args-fn (create-replace-factory-args-fn parameters container)
        replaced-factory-args (walk/prewalk replace-factory-args-fn factory-args)
        component (apply factory-fn replaced-factory-args)]
    (assoc container component-id component)))

(defn- component-id-comparator [component-ids]
  (fn [a b]
    (- (.indexOf component-ids a) (.indexOf component-ids b))))

(defn create-container
  "Using supplied container specification and parameters map, creates a container
  that is actually just a sorted map of [component-id component] entries. Entries are
  topologically sorted starting from components that have no dependencies, to the ones
  that are on top of dependency graph. Specified depedency graph has to be acyclic (DAG)
  or exception is thrown otherwise.

  Container specification is map of [component-id component-spec] entries, where component-spec
  is [component-factory-fn & args] vector. Each component is constructed by evaluating factory
  function with supplied arguments.

  If some factory function argument is a component reference (constructed via comp-ref function),
  it is replaced with the referred component (ie. dependency injection). Similarly, if argument
  is a parameter reference (constructed via param-ref function), it is replaced with the referred
  parameter, taken from supplied parameter map.

  Usage example:

  (create-container
    {:my-comp-1 [mycompany.myapp/map->MyComp1 {:limit     221
                                               :max-count 66
                                               :comp-2    (comp-ref :my-comp-2)}]
     :my-comp-2 [mycompany.myapp/map->MyComp2 {:admin-email \"admin@mycompany.com\"
                                               :smtp-server (param-ref :smtp :server)
                                               :smtp-port   (param-ref :smtp :port)}]}
    {:smtp {:server \"mail.mycompany.com\"
            :port   25}})

  "
  ([container-spec]
    (create-container container-spec {}))
  ([container-spec parameters]
    {:pre [(map? container-spec)
           (map? parameters)
           (every? vector? (vals container-spec))
           (every? (complement empty?) (vals container-spec))]}
    (let [empty-sorted-map (->>
                             container-spec
                             topo-sort-component-ids
                             component-id-comparator
                             sorted-map-by)
          sorted-container-spec (into empty-sorted-map container-spec)]
      (reduce (partial create-component parameters) empty-sorted-map sorted-container-spec))))


(defprotocol Lifecycle
  "This interface is implemented by components to perform start/stop logic."
  (start
    [this]
    "Starts the component. Returns nil.")
  (stop
    [this]
    "Stops the component. Returns nil."))

(defn- stop-entries
  "Stops components in given container entries that satisfy Lifecycle protocol. Returns nil."
  [container-entries]
  (doseq [[component-id component] (rseq container-entries)]
    (when (satisfies? Lifecycle component)
      (log/debugf "Stopping component '%s'" component-id)
      (try
        (stop component)
        (catch Throwable th
          (log/errorf th "Error while stopping component '%s': %s" component-id th))))))

(defn stop-container
  "Stops all contained components that satisfy Lifecycle protocol. Returns nil."
  [container]
  (stop-entries container))

(defn- start-component
  "Starts component in given container entry. In case of failure, all given processed components
  are stopped and exception is rethrown."
  [[component-id component] processed-entries]
  (when (satisfies? Lifecycle component)
    (try
      (log/debugf "Starting component '%s'" component-id)
      (start component)
      (catch Throwable th
        (stop-entries processed-entries)
        (throw (IllegalStateException. (str "Failed starting component '" component-id "': " (.getMessage th)) th))))))

(defn start-container
  "Starts all contained components that satisfy Lifecycle protocol. Returns nil.
  If some component startup fails, all already started components get stopped."
  [container]
  (loop [unprocessed-entries container
         processed-entries []]
    (when-let [[entry & rest-of-unprocessed-entries] (seq unprocessed-entries)]
      (start-component entry processed-entries)
      (recur rest-of-unprocessed-entries (conj processed-entries entry)))))
