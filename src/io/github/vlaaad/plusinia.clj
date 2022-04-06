(ns io.github.vlaaad.plusinia
  (:require [com.walmartlabs.lacinia.schema :as l.schema]
            [com.walmartlabs.lacinia.executor :as l.executor]
            [com.walmartlabs.lacinia :as l]))

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

(defprotocol QueryFetcher
  :extend-via-metadata true
  (get-fetcher [this])
  (get-context-keys [this]))

(extend-protocol QueryFetcher
  Object
  (get-fetcher [this] this)
  (get-context-keys [this]))

(defn make-query-fetcher [fetcher & {:keys [context-keys]}]
  (with-meta {::fetcher fetcher ::context-keys context-keys}
             {`get-fetcher ::fetcher `get-context-keys ::context-keys}))

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
                                                 " is abstract, but " input-node
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
                                (not= requested-type node-type)))]
            [{:fetcher (require-fetcher type->field->fetcher (or node-type requested-type) requested-field)
              :field requested-field
              :args (:args selector)
              :ctx (get-context input-node)}
             input-node
             selector]))
        selector->input-node->output-node-or-nodes
        (reduce
          (fn [acc [selector input-node output-node-or-nodes]]
            (update acc selector assoc input-node output-node-or-nodes))
          {}
          ;; todo parallelize
          (for [[{:keys [fetcher args ctx field]} input-node->selectors] fetch-key->input-node->selectors
                :let [values (into #{} (map get-value) (keys input-node->selectors))
                      value->output-node-or-nodes (fetcher ctx args values)
                      _ (when-not (and (map? value->output-node-or-nodes)
                                       (every? #(contains? value->output-node-or-nodes %) values)
                                       (= (count value->output-node-or-nodes) (count values)))
                          (throw (ex-info (str "Invalid result by " field " fetcher")
                                          {:result value->output-node-or-nodes
                                           :values values
                                           :args args
                                           :field field
                                           :fetcher fetcher})))]
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
              (if-let [sub-selection (:selection selector)] ;; FIXME or if output nodes have type?
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
                      (map (juxt key #(-> % val (map-or-invoke get-value))))
                      input-node->output-node-or-nodes))))))
      selector->input-nodes)))

(defn- query-fetcher->field-fetcher [query-fetcher]
  (fn [ctx args values]
    (zipmap values (repeat ((get-fetcher query-fetcher) ctx args)))))

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

(defn parse-version-ref [str]
  (or ({"latest" :latest "next" :next} str)
      (and (string? str) (Integer/parseInt str))
      (and (int? str) str)
      (throw (ex-info "Invalid version ref" {:ref str}))))

(defn serialize-version-ref [version-ref]
  ({:latest "latest" :next "next"} version-ref version-ref))

(def schema
  (l.schema/compile
    (wrap-schema
      {:queries {:concepts {:type '(non-null (list (non-null :Concept)))
                            :args {:version {:type '(non-null :VersionRef)
                                             :description "Use this taxonomy version"
                                             :default-value :latest}
                                   :type {:type 'String}}}
                 :changes {:type '(non-null (list (non-null :Event)))}
                 :entities {:type '(non-null (list (non-null :Entity)))}}
       :scalars {:VersionRef
                 {:description "Version identifier, either \"latest\", \"next\" (requires admin rights) or a number of a version"
                  :parse parse-version-ref
                  :serialize serialize-version-ref}}
       :unions {:Entity {:members [:Concept :Created :Updated :Deleted]}}
       :interfaces {:Event {:fields {:id {:type '(non-null String)}
                                     :concept {:type '(non-null :Concept)}}}}
       :objects {:Concept {:fields {:id {:type '(non-null String)}
                                    :alternative_labels {:type '(non-null (list (non-null String)))}
                                    :preferred_label {:type '(non-null String)}
                                    :broader {:type '(non-null (list (non-null :Concept)))
                                              :args {:type {:type 'String}}}
                                    :related {:type '(non-null (list (non-null :Concept)))
                                              :args {:type {:type 'String}}}}}
                 :Created {:implements [:Event]
                           :fields {:id {:type '(non-null String)}
                                    :concept {:type '(non-null :Concept)}
                                    :createdAt {:type '(non-null String)}}}
                 :Updated {:implements [:Event]
                           :fields {:id {:type '(non-null String)}
                                    :concept {:type '(non-null :Concept)}
                                    :updatedAt {:type '(non-null String)}}}
                 :Deleted {:implements [:Event]
                           :fields {:id {:type '(non-null String)}
                                    :concept {:type '(non-null :Concept)}
                                    :deletedAt {:type '(non-null String)}}}}}
      {:Query {:concepts (fn [_ _] [(make-node 1 :context {:db :the-db})
                                    (make-node 2 :context {:db :the-db})
                                    (make-node 4 :context {:db :the-db})])
               :changes (fn [_ _]
                          [(make-node [1 "2021-04-14"] :type :Created)
                           (make-node [2 "2021-04-14"] :type :Created)
                           (make-node [1 "2021-05-01"] :type :Updated)
                           (make-node [1 "2021-05-09"] :type :Deleted)])
               :entities (fn [_ _]
                           [(make-node 1 :context {:db :the-db} :type :Concept)
                            (make-node 2 :context {:db :the-db} :type :Concept)
                            (make-node 4 :context {:db :the-db} :type :Concept)
                            (make-node [1 "2021-04-14"] :type :Created)
                            (make-node [2 "2021-04-14"] :type :Created)
                            (make-node [1 "2021-05-01"] :type :Updated)
                            (make-node [1 "2021-05-09"] :type :Deleted)])}
       :Event {:concept #(zipmap %3 (map first %3))}
       :Entity {:id #(zipmap %3 (map (fn [s]
                                       (make-node (str s) :context {:oh :no})) %3))}
       :Created {:createdAt #(zipmap %3 (map second %3))}
       :Updated {:updatedAt #(zipmap %3 (map second %3))
                 :id #(zipmap %3 (map (fn [s]
                                        (str "upd_" s)) %3))}
       :Deleted {:deletedAt #(zipmap %3 (map second %3))}
       :Concept {:alternative_labels #(zipmap %3 (map (fn [s]
                                                        [(make-node (str "the " s) :context {:oh :no})
                                                         (make-node (str s) :context {:oh :no})]) %3))

                 :preferred_label #(zipmap %3 (map (partial str "label_") %3))
                 :related #(zipmap %3 (map (juxt (comp (fn [id]
                                                         (make-node id :context {:related :context})) inc)) %3))
                 :broader #(zipmap %3 (map (juxt (comp (fn [id]
                                                         (make-node id :context {:broader :context})) dec)) %3))}})))

(comment
  (require 'lambdaisland.deep-diff2)

  (lambdaisland.deep-diff2/diff
    (l/execute
      schema
      "{entities {
          e: __typename
          ... on Concept {id}
          ... on Created {id}
          ... on Updated {id}
          ... on Deleted {id}}}"
      {}
      {})
    {:data {:entities [{:e :Concept :id "1"}
                       {:e :Concept :id "2"}
                       {:e :Concept :id "4"}
                       {:e :Created :id "[1 \"2021-04-14\"]"}
                       {:e :Created :id "[2 \"2021-04-14\"]"}
                       {:e :Updated :id "upd_[1 \"2021-05-01\"]"}
                       {:e :Deleted :id "[1 \"2021-05-09\"]"}]}})

  (lambdaisland.deep-diff2/diff
    (l/execute
      schema
      "{changes {
          id
          ... on Updated {
            also_id: id
          }
       }}"
      {}
      {})
    {:data {:changes [{:id "[1 \"2021-04-14\"]"}
                      {:id "[2 \"2021-04-14\"]"}
                      {:id "upd_[1 \"2021-05-01\"]" :also_id "upd_[1 \"2021-05-01\"]"}
                      {:id "[1 \"2021-05-09\"]"}]}})

  (lambdaisland.deep-diff2/diff
    (l/execute
      schema
      "{concepts {
          id
          broader {
            id
            also_id: id
            alternative_labels
            broader {
              id
              related { id preferred_label }
              broader { id preferred_label }}}}}"
      {}
      {})
    {:data {:concepts [{:id "1"
                        :broader [{:id "0"
                                   :also_id "0"
                                   :alternative_labels ["the 0" "0"]
                                   :broader [{:id "-1"
                                              :related [{:id "0" :preferred_label "label_0"}]
                                              :broader [{:id "-2" :preferred_label "label_-2"}]}]}]}
                       {:id "2"
                        :broader [{:id "1"
                                   :also_id "1"
                                   :alternative_labels ["the 1" "1"]
                                   :broader [{:id "0"
                                              :related [{:id "1" :preferred_label "label_1"}]
                                              :broader [{:id "-1" :preferred_label "label_-1"}]}]}]}
                       {:id "4"
                        :broader [{:id "3"
                                   :also_id "3"
                                   :alternative_labels ["the 3" "3"]
                                   :broader [{:id "2"
                                              :related [{:id "3" :preferred_label "label_3"}]
                                              :broader [{:id "1" :preferred_label "label_1"}]}]}]}]}})

  ,)
