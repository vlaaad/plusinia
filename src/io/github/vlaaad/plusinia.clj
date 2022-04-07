(ns io.github.vlaaad.plusinia
  (:require [com.walmartlabs.lacinia.schema :as l.schema]
            [com.walmartlabs.lacinia.executor :as l.executor]))

(defprotocol Node
  :extend-via-metadata true
  (get-value [this])
  (get-context [this])
  (get-type [this]))

(extend-protocol Node
  Object
  (get-value [this] this)
  (get-context [_])
  (get-type [_])

  nil
  (get-value [this] this)
  (get-context [_])
  (get-type [_]))

(defn make-node [value & {:keys [context type]}]
  (with-meta {::value value ::context context ::type type}
             {`get-value ::value `get-context ::context `get-type ::type}))

(defn- add-context-to-node [node parent-context]
  (make-node
    (get-value node)
    :context (merge parent-context (get-context node))
    :type (get-type node)))

(defn- get-fetch-fn [fetcher]
  (cond-> fetcher (map? fetcher) :fn))

(defn- get-context-keys [fetcher]
  (when (map? fetcher) (:context-keys fetcher)))

(defn- default-batch-fn [ctx args _]
  (when (or ctx args)
    (conj (or ctx {}) args)))

(defn- get-batch-fn [fetcher]
  (if (map? fetcher) (:batch-fn fetcher) default-batch-fn))

(defn make-query-fetcher [fn & {:keys [context-keys batch-fn]
                                :or {batch-fn default-batch-fn}}]
  {:fn fn :context-keys context-keys :batch-fn batch-fn})

(defn make-field-fetcher [fn & {:keys [batch-fn]}]
  {:pre [batch-fn]}
  {:fn fn :batch-fn batch-fn})

(defn- make-selector [& {:keys [type field args selection]}]
  {:pre [field]}
  (cond-> {:field field}
          type (assoc :type type)
          (pos? (count args)) (assoc :args args)
          (pos? (count selection)) (assoc :selection selection)))

(defn- lacinia-selections->selection [selections]
  (->> selections
       (mapcat (fn [[k args+selections]]
                 (let [type (keyword (namespace k))
                       field (keyword (name k))]
                   (map (fn [{:keys [args selections]}]
                          (make-selector
                            :type type
                            :field field
                            :args args
                            :selection (lacinia-selections->selection selections)))
                        args+selections))))
       set))

(defn- selection [ctx]
  (lacinia-selections->selection (l.executor/selections-tree ctx)))

(defn- getter [field]
  (fn [ctx args value]
    (let [k (make-selector :field field :args args :selection (selection ctx))]
      (get value k))))

(defn- sequential-or-set? [x]
  (or (sequential? x) (set? x)))

(defn- collify [x-or-xs]
  (if (sequential-or-set? x-or-xs)
    x-or-xs
    [x-or-xs]))

(defn- map-or-invoke [x-or-xs f]
  (if (sequential-or-set? x-or-xs)
    (mapv f x-or-xs)
    (f x-or-xs)))

(defn- require-fetcher [type->field->fetcher type field]
  (-> type->field->fetcher
      (get type)
      (get field)
      (or (throw (ex-info (str "No fetcher for " [type field])
                          {:keys [type field] :fetchers type->field->fetcher})))))

(defn- accumulate-to-sets [kvs]
  (reduce
    (fn [acc [k vs]]
      (cond-> acc (seq vs) (update k (fnil into #{}) vs)))
    {}
    kvs))

;; selection->nodes: a map or coll of kvs, where key is selector and val is a coll of nodes
;; field->fetcher: a map of field keywords to fetchers (fn of ctx, args, values -> value->node-or-nodes)
;; returns: a map of selection->input-node->output-values (not nodes!)
(defn- fetch [selector->input-nodes type->field->fetcher type->instance-of]
  (let [fetch-key->input-node->selectors
        (reduce
          (fn [acc [key input-node selector]]
            (update-in acc [key input-node] (fnil conj #{}) selector))
          {}
          (for [[selector input-nodes] selector->input-nodes
                input-node input-nodes
                :let [requested-type (:type selector)
                      requested-field (:field selector)
                      request-concrete-type (some? (type->instance-of requested-type))
                      node-type (get-type input-node)
                      _ (when (not request-concrete-type)
                          (when (nil? node-type)
                            (throw (ex-info (str "Selected field " requested-field
                                                 " of type " requested-type
                                                 " is abstract, but " (get-value input-node)
                                                 " node does not specify its type")
                                            {:field requested-field
                                             :type requested-type
                                             :node input-node})))
                          (when (not ((type->instance-of node-type) requested-type))
                            (throw (ex-info (str "Input node is of type " node-type ", but " requested-type " was requested")
                                            {:field requested-field
                                             :type requested-type
                                             :node input-node}))))]
                ;; skip when requested type is concrete and not equal to node type
                :when (not (and request-concrete-type
                                (some? node-type)
                                (not= requested-type node-type)))
                :let [fetcher (require-fetcher type->field->fetcher (or node-type requested-type) requested-field)]]
            [{:fetch-fn (get-fetch-fn fetcher)
              :batch ((get-batch-fn fetcher) (get-context input-node) (:args selector) (get-value input-node))}
             input-node
             selector]))
        selector->input-node->output-node-or-nodes
        (reduce
          (fn [acc [selector input-node output-node-or-nodes]]
            (update acc selector assoc input-node output-node-or-nodes))
          {}
          ;; todo parallelize
          (for [[{:keys [fetch-fn batch]} input-node->selectors] fetch-key->input-node->selectors
                :let [values (into #{} (map get-value) (keys input-node->selectors))
                      value->output-node-or-nodes (fetch-fn batch values)
                      _ (when-not (and (map? value->output-node-or-nodes)
                                       (every? #(contains? value->output-node-or-nodes %) values)
                                       (= (count value->output-node-or-nodes) (count values)))
                          (throw (ex-info (str "Invalid result by " fetch-fn " fetch-fn")
                                          {:result value->output-node-or-nodes
                                           :values values
                                           :fetch-fn fetch-fn
                                           :batch batch})))]
                [input-node selectors] input-node->selectors
                selector selectors]
            [selector input-node (value->output-node-or-nodes (get-value input-node))]))

        sub-selection (->> selector->input-nodes
                           (filter #(-> % key :selection))
                           (mapcat
                             (fn [[selector input-nodes]]
                               (let [sub-selection (:selection selector)
                                     selection-input-node->output-node-or-nodes
                                     (-> selector->input-node->output-node-or-nodes
                                         (get selector)
                                         (select-keys input-nodes))
                                     sub-input-nodes
                                     (set
                                       (for [[input-node output-node-or-nodes] selection-input-node->output-node-or-nodes
                                             output-node (collify output-node-or-nodes)]
                                         (add-context-to-node output-node (get-context input-node))))]
                                 (map vector sub-selection (repeat sub-input-nodes)))))
                           accumulate-to-sets)
        ;; sub-input-node is an output-node with context added from corresponding input-node
        sub-selector->sub-input-node->sub-output-value
        (if (pos? (count sub-selection))
          (fetch sub-selection type->field->fetcher type->instance-of)
          {})]
    (into
      {}
      (map
        (juxt
          key
          (fn [[selector input-nodes]]
            (let [input-node->output-node-or-nodes
                  (-> selector->input-node->output-node-or-nodes
                      (get selector)
                      (select-keys input-nodes))]
              (if-let [sub-selection (:selection selector)]
                (into
                  {}
                  (map
                    (juxt
                      key
                      (fn [[input-node output-node-or-nodes]]
                        (map-or-invoke
                          output-node-or-nodes
                          (fn [output-node]
                            (let [t (get-type output-node)]
                              (cond-> (into {}
                                            (keep
                                              (fn [sub-selector]
                                                (let [sub-input-node->sub-output-value
                                                      (get sub-selector->sub-input-node->sub-output-value sub-selector)
                                                      sub-input-node (add-context-to-node output-node (get-context input-node))]
                                                  ;; might be absent if skipped loading because of different type
                                                  (when (contains? sub-input-node->sub-output-value sub-input-node)
                                                    [(dissoc sub-selector :type)
                                                     (sub-input-node->sub-output-value sub-input-node)]))))
                                            sub-selection)
                                      t
                                      (l.schema/tag-with-type t))))))))
                  input-node->output-node-or-nodes)
                (into {}
                      (map
                        (juxt
                          key
                          #(-> % val (map-or-invoke (fn [output-node]
                                                      (let [t (get-type output-node)]
                                                        (cond-> (get-value output-node)
                                                                t
                                                                (l.schema/tag-with-type t))))))))
                      input-node->output-node-or-nodes))))))
      selector->input-nodes)))

(defn- query-fetcher->field-fetcher [query-fetcher]
  (let [fetch-fn (get-fetch-fn query-fetcher)]
    (make-field-fetcher
      (fn [key values]
        (zipmap values (repeat (fetch-fn key))))
      :batch-fn (get-batch-fn query-fetcher))))

(defn wrap-schema [schema fetchers]
  (let [type->parents (update-vals (group-by first (concat
                                                     (for [[child {:keys [implements]}] (:objects schema)
                                                           parent implements]
                                                       [child parent])
                                                     (for [[parent {:keys [members]}] (:unions schema)
                                                           child members]
                                                       [child parent])))
                                   #(mapv second %))
        type->instance-of (->> schema
                               :objects
                               keys
                               (cons :Query)
                               (map (juxt identity #(into #{%} (type->parents %))))
                               (into {}))
        type->field->fetcher (reduce-kv
                               (fn [acc type parents]
                                 (let [defaults (mapv acc parents)]
                                   (update acc type #(apply merge (conj defaults %)))))
                               (update fetchers :Query update-vals query-fetcher->field-fetcher)
                               type->parents)]
    (-> schema
        (update :queries
                (fn [qs]
                  (->> qs
                       (map
                         (juxt
                           key
                           (fn [[query-name definition]]
                             (let [context-keys (get-context-keys (require-fetcher fetchers :Query query-name))]
                               (assoc definition :resolve (fn [ctx args v]
                                                            (let [node (make-node v :context (not-empty (select-keys ctx context-keys)))
                                                                  k (make-selector :type :Query
                                                                                   :field query-name
                                                                                   :args args
                                                                                   :selection (selection ctx))]
                                                              (-> (fetch {k [node]} type->field->fetcher type->instance-of)
                                                                  (get k)
                                                                  (get node)))))))))
                       (into {}))))
        (update :objects
                (fn [os]
                  (->> os
                       (map (fn [[object-name definition]]
                              [object-name
                               (update definition
                                       :fields
                                       (fn [fs]
                                         (->> fs
                                              (map (fn [[field-name field-definition]]
                                                     (require-fetcher type->field->fetcher object-name field-name)
                                                     [field-name (assoc field-definition
                                                                   :resolve (getter field-name))]))
                                              (into {}))))]))
                       (into {})))))))
