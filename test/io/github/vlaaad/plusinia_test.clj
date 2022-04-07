(ns io.github.vlaaad.plusinia-test
  (:require [io.github.vlaaad.plusinia :as p]
            [com.walmartlabs.lacinia :as l]
            [clojure.test :refer :all]
            [com.walmartlabs.lacinia.schema :as l.schema]))

(def ^:private ^:dynamic *invocations*)

(defn- wrap-count-invocations [f]
  (with-meta (fn [& args]
               (swap! *invocations* conj (into [f] args))
               (apply f args))
             (meta f)))

(defmacro with-invocation-counter [& body]
  `(binding [*invocations* (atom #{})]
     (let [result# (do ~@body)
           invocations# @*invocations*]
       {:result result#
        :invocations invocations#})))

(defn- fetch-2-concepts [_]
  [1 2])

(defn- fetch-identity [_ values]
  (zipmap values values))

(defn- fetch-identity-or-theify [{:keys [theify]} values]
  (zipmap values (if theify
                   (map #(str "the " %) values)
                   values)))

(defn- fetch-related [_ values]
  (zipmap values
          (map #(-> % inc (p/make-node :context {:related true}) vector) values)))

(defn- fetch-broader [_ values]
  (zipmap values
          (map #(-> % dec (p/make-node :context {:broader true}) vector) values)))

(deftest plusinia-batches-requests
  (testing "across fields"
    (let [s (l.schema/compile
              (p/wrap-schema '{:queries {:concepts {:type (list :Concept)}}
                               :objects {:Concept {:fields {:id {:type String}}}}}
                             {:Query {:concepts (wrap-count-invocations fetch-2-concepts)}
                              :Concept {:id (wrap-count-invocations fetch-identity)}}))]
      (is (= {:invocations #{[fetch-2-concepts nil]
                             [fetch-identity nil #{1 2}]}
              :result {:data {:concepts [{:id "1"}
                                         {:id "2"}]}}}
             (with-invocation-counter
               (l/execute s "{concepts {id}}" {} {}))))))
  (testing "across args"
    (let [s (l.schema/compile
              (p/wrap-schema '{:queries {:concepts {:type (list :Concept)}}
                               :objects {:Concept {:fields {:id {:type String
                                                                 :args {:theify {:type (non-null Boolean)
                                                                                 :default-value false}}}}}}}
                             {:Query {:concepts (wrap-count-invocations fetch-2-concepts)}
                              :Concept {:id (wrap-count-invocations fetch-identity-or-theify)}}))]
      (is (= {:invocations #{[fetch-2-concepts nil]
                             [fetch-identity-or-theify {:theify true} #{1 2}]
                             [fetch-identity-or-theify {:theify false} #{1 2}]}
              :result {:data {:concepts [{:id "1" :the_id "the 1"}
                                         {:id "2" :the_id "the 2"}]}}}
             (with-invocation-counter
               (l/execute s "{concepts {
                                id
                                the_id: id(theify: true)}}" {} {}))))))
  (testing "across contexts"
    (let [s (l.schema/compile
              (p/wrap-schema '{:queries {:concepts {:type (list :Concept)}}
                               :objects {:Concept {:fields {:id {:type String}
                                                            :related {:type (list :Concept)}
                                                            :broader {:type (list :Concept)}}}}}
                             {:Query {:concepts (wrap-count-invocations fetch-2-concepts)}
                              :Concept {:id (wrap-count-invocations fetch-identity)
                                        :related (wrap-count-invocations fetch-related)
                                        :broader (wrap-count-invocations fetch-broader)}}))]
      (is (= {:invocations #{[fetch-2-concepts nil]
                             [fetch-related nil #{1 2}]
                             [fetch-broader nil #{1 2}]
                             [fetch-identity {:broader true} #{0 1}]
                             [fetch-identity {:related true} #{3 2}]}
              :result {:data {:concepts [{:related [{:id "2"}]
                                          :broader [{:id "0"}]}
                                         {:related [{:id "3"}]
                                          :broader [{:id "1"}]}]}}}
             (with-invocation-counter
               (l/execute s "{concepts {
                                related {id}
                                broader {id}}}" {} {}))))))
  (testing "at depth"
    (let [s (l.schema/compile
              (p/wrap-schema '{:queries {:concepts {:type (list :Concept)}}
                               :objects {:Concept {:fields {:id {:type String}
                                                            :related {:type (list :Concept)}
                                                            :broader {:type (list :Concept)}}}}}
                             {:Query {:concepts (wrap-count-invocations fetch-2-concepts)}
                              :Concept {:id (wrap-count-invocations fetch-identity)
                                        :related (wrap-count-invocations fetch-related)
                                        :broader (wrap-count-invocations fetch-broader)}}))]
      (is (= {:invocations #{;; depth 0: concepts
                             [fetch-2-concepts nil]
                             ;; depth 1: related and broader
                             [fetch-related nil #{1 2}]
                             [fetch-broader nil #{1 2}]
                             ;; depth 2: broader
                             [fetch-broader {:related true} #{3 2}]
                             [fetch-broader {:broader true} #{0 1}]
                             ;; depth 3: ids
                             [fetch-identity {:related true :broader true} #{1 2}]
                             [fetch-identity {:broader true} #{0 -1}]}
              :result {:data {:concepts [{:related [{:broader [{:id "1"}]}]
                                          :broader [{:broader [{:id "-1"}]}]}
                                         {:related [{:broader [{:id "2"}]}]
                                          :broader [{:broader [{:id "0"}]}]}]}}}
             (with-invocation-counter
               (l/execute s "{concepts {
                                related { broader { id }}
                                broader { broader { id }}}}" {} {})))))))

(deftest plusinia-accumulates-contexts
  (let [s (l.schema/compile
            (p/wrap-schema '{:queries {:concepts {:type (list :Concept)}}
                             :objects {:Concept {:fields {:id {:type String}
                                                          :related {:type (list :Concept)}
                                                          :broader {:type (list :Concept)}}}}}
                           {:Query {:concepts (wrap-count-invocations fetch-2-concepts)}
                            :Concept {:id (wrap-count-invocations fetch-identity)
                                      :related (wrap-count-invocations fetch-related)
                                      :broader (wrap-count-invocations fetch-broader)}}))]
    (is (= {:invocations #{;; depth 0: contexts
                           [fetch-2-concepts nil]
                           ;; depth 1: related, adds :related true
                           [fetch-related nil #{1 2}]
                           ;; depth 2: broader, adds :broader true
                           [fetch-broader {:related true} #{3 2}]
                           ;; depth 3: ids, has both :related and :broader
                           [fetch-identity {:related true :broader true} #{1 2}]}
            :result {:data {:concepts [{:related [{:broader [{:id "1"}]}]}
                                       {:related [{:broader [{:id "2"}]}]}]}}}
           (with-invocation-counter
             (l/execute s "{concepts { related { broader { id }} }}" {} {}))))))

(defn- fetch-events [_]
  [(p/make-node [1 "2021-04-14"] :type :Created)
   (p/make-node [2 "2021-04-14"] :type :Created)
   (p/make-node [3 "2021-05-01"] :type :Updated)
   (p/make-node [4 "2021-05-09"] :type :Deleted)])

(defn- fetch-event-ids [_ vals]
  (zipmap vals (map first vals)))

(defn- fetch-event-times [_ vals]
  (zipmap vals (map second vals)))

(defn- fetch-update-event-ids [_ vals]
  (zipmap vals (map #(str "upd_" (first %)) vals)))

(deftest plusinia-supports-interfaces
  (testing "allows impls on interfaces and overrides"
    (let [s (l.schema/compile
              (p/wrap-schema
                '{:queries {:changes {:type (non-null (list (non-null :Event)))}}
                  :interfaces {:Event {:fields {:id {:type (non-null String)}}}}
                  :objects {:Created {:implements [:Event]
                                      :fields {:id {:type (non-null String)}
                                               :createdAt {:type (non-null String)}}}
                            :Updated {:implements [:Event]
                                      :fields {:id {:type (non-null String)}
                                               :updatedAt {:type (non-null String)}}}
                            :Deleted {:implements [:Event]
                                      :fields {:id {:type (non-null String)}
                                               :deletedAt {:type (non-null String)}}}}}
                {:Query {:changes (wrap-count-invocations fetch-events)}
                 :Event {:id (wrap-count-invocations fetch-event-ids)}
                 :Created {:createdAt (wrap-count-invocations fetch-event-times)}
                 :Updated {:updatedAt (wrap-count-invocations fetch-event-times)
                           :id (wrap-count-invocations fetch-update-event-ids)}
                 :Deleted {:deletedAt (wrap-count-invocations fetch-event-times)}}))]
      (is (= {:invocations #{[fetch-events nil]
                             [fetch-event-ids nil #{[1 "2021-04-14"] [2 "2021-04-14"] [4 "2021-05-09"]}]
                             [fetch-event-times nil #{[1 "2021-04-14"] [2 "2021-04-14"]}]
                             [fetch-update-event-ids nil #{[3 "2021-05-01"]}]}
              :result {:data {:changes [{:event :Created :id "1" :createdAt "2021-04-14"}
                                        {:event :Created :id "2" :createdAt "2021-04-14"}
                                        {:event :Updated :id "upd_3"}
                                        {:event :Deleted :id "4"}]}}}
             (with-invocation-counter
               (l/execute s "{changes {
                                event: __typename
                                id
                                ... on Created { createdAt }}}" {} {}))))))
  (testing "allows queries without sub-selections (only type info)"
    (let [s (l.schema/compile
              (p/wrap-schema
                '{:queries {:changes {:type (non-null (list (non-null :Event)))}}
                  :interfaces {:Event {:fields {:id {:type (non-null String)}}}}
                  :objects {:Created {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}
                            :Updated {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}
                            :Deleted {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}}}
                {:Query {:changes (fn [_]
                                    [(p/make-node {:id 1 :time "2021-04-14"} :type :Created)
                                     (p/make-node {:id 2 :time "2021-04-14"} :type :Created)
                                     (p/make-node {:id 3 :time "2021-05-01"} :type :Updated)
                                     (p/make-node {:id 4 :time "2021-05-09"} :type :Deleted)])}
                 :Event {:id fetch-event-ids}}))]
      (is (= {:data {:changes [{:type :Created}
                               {:type :Created}
                               {:type :Updated}
                               {:type :Deleted}]}}
             (l/execute s "{ changes { type: __typename } }" {} {})))))
  (testing "throws if query to abstract type does not return typed nodes"
    (let [s (l.schema/compile
              (p/wrap-schema
                '{:queries {:changes {:type (non-null (list (non-null :Event)))}}
                  :interfaces {:Event {:fields {:id {:type (non-null String)}}}}
                  :objects {:Created {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}
                            :Updated {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}
                            :Deleted {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}}}
                {:Query {:changes (fn [_]
                                    [(p/make-node [1 "2021-04-14"] :type :Created)
                                     [2 "2021-04-14"]
                                     (p/make-node [3 "2021-05-01"] :type :Updated)
                                     (p/make-node [4 "2021-05-09"] :type :Deleted)])}
                 :Event {:id fetch-event-ids}}))]
      (is (thrown-with-msg? Exception #"Selected field :id of type :Event is abstract, but \[2 \"2021-04-14\"\] node does not specify its type"
                            (l/execute s "{ changes { id } }" {} {})))))
  (testing "throws if query to abstract type returns nodes of incompatible type"
    (let [s (l.schema/compile
              (p/wrap-schema
                '{:queries {:changes {:type (non-null (list (non-null :Event)))}}
                  :interfaces {:Event {:fields {:id {:type (non-null String)}}}}
                  :objects {:Created {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}
                            :Updated {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}
                            :Deleted {:implements [:Event]
                                      :fields {:id {:type (non-null String)}}}
                            :Concept {:fields {:id {:type (non-null String)}}}}}
                {:Query {:changes (fn [_]
                                    [(p/make-node [1 "2021-04-14"] :type :Created)
                                     (p/make-node 2 :type :Concept)
                                     (p/make-node [3 "2021-05-01"] :type :Updated)
                                     (p/make-node [4 "2021-05-09"] :type :Deleted)])}
                 :Event {:id fetch-event-ids}
                 :Concept {:id fetch-identity}}))]
      (is (thrown-with-msg? Exception #"Input node is of type :Concept, but :Event was requested"
                            (l/execute s "{ changes { id } }" {} {}))))))

(defn- fetch-entities [_]
  [(p/make-node 1 :type :Concept)
   (p/make-node 2 :type :Concept)
   (p/make-node [1 "2021-04-14"] :type :Created)
   (p/make-node [2 "2021-04-14"] :type :Created)
   (p/make-node [3 "2021-05-01"] :type :Updated)
   (p/make-node [4 "2021-05-09"] :type :Deleted)])

(deftest plusinia-supports-unions
  (let [s (l.schema/compile
            (p/wrap-schema
              '{:queries {:entities {:type (non-null (list (non-null :Entity)))}}
                :unions {:Entity {:members [:Concept :Created :Updated :Deleted]}}
                :interfaces {:Event {:fields {:id {:type (non-null String)}}}}
                :objects {:Concept {:fields {:id {:type (non-null String)}}}
                          :Created {:implements [:Event]
                                    :fields {:id {:type (non-null String)}}}
                          :Updated {:implements [:Event]
                                    :fields {:id {:type (non-null String)}}}
                          :Deleted {:implements [:Event]
                                    :fields {:id {:type (non-null String)}}}}}
              {:Query {:entities (wrap-count-invocations fetch-entities)}
               :Event {:id (wrap-count-invocations fetch-event-ids)}
               :Concept {:id (wrap-count-invocations fetch-identity)}}))]
    (is (= {:invocations #{[fetch-entities nil]
                           [fetch-identity nil #{1 2}]
                           [fetch-event-ids nil #{[1 "2021-04-14"] [2 "2021-04-14"] [3 "2021-05-01"]}]}
            :result {:data {:entities [{:type :Concept :id "1"}
                                       {:type :Concept :id "2"}
                                       {:type :Created :created_id "1"}
                                       {:type :Created :created_id "2"}
                                       {:type :Updated :id "3"}
                                       {:type :Deleted}]}}}
           (with-invocation-counter
             (l/execute s "{entities {
                              type: __typename
                              ... on Concept { id }
                              ... on Created { created_id: id }
                              ... on Updated { id }}}" {} {}))))))

(defn- fetch-with-ctx-transform [{:keys [str]} vals]
  (zipmap vals (map str vals)))

(defn- wrap-in-underscores [x]
  (str "_" x "_"))

(deftest plusinia-supports-requesting-data-from-lacinia-context
  (let [s (l.schema/compile
            (p/wrap-schema
              '{:queries {:concepts {:type (non-null (list (non-null :Concept)))}}
                :objects {:Concept {:fields {:id {:type (non-null String)}}}}}
              {:Query {:concepts (p/make-query-fetcher
                                   (wrap-count-invocations fetch-2-concepts)
                                   :context-keys #{:str})}
               :Concept {:id (wrap-count-invocations fetch-with-ctx-transform)}}))]
    (is (= {:invocations #{[fetch-2-concepts {:str str}]
                           [fetch-with-ctx-transform {:str str} #{1 2}]}
            :result {:data {:concepts [{:id "1"} {:id "2"}]}}}
           (with-invocation-counter
             (l/execute s "{concepts { id }}" {} {:str str}))))
    (is (= {:invocations #{[fetch-2-concepts {:str wrap-in-underscores}]
                           [fetch-with-ctx-transform {:str wrap-in-underscores} #{1 2}]}
            :result {:data {:concepts [{:id "_1_"} {:id "_2_"}]}}}
           (with-invocation-counter
             (l/execute s "{concepts { id }}" {} {:str wrap-in-underscores}))))))

(defn- batch-by-shape [_ _ value]
  (:shape value))

(defn- fetch-shapes [_]
  [{:shape :rect :width 10 :height 20}
   {:shape :rect :width 20 :height 10}
   {:shape :square :width 15}])

(defn- fetch-shape-type [_ vals]
  (zipmap vals (map (comp name :shape) vals)))

(defn- fetch-area [shape values]
  (let [area-fn (case shape
                  :rect #(* (:width %) (:height %))
                  :square #(* (:width %) (:width %)))]
    (zipmap values (map area-fn values))))

(deftest plusinia-allows-custom-keys
  (let [s (l.schema/compile
            (p/wrap-schema
              '{:queries {:shapes {:type (non-null (list (non-null :Shape)))}}
                :objects {:Shape {:fields {:type {:type (non-null String)}
                                           :area {:type (non-null Int)}}}}}
              {:Query {:shapes (wrap-count-invocations fetch-shapes)}
               :Shape {:type fetch-shape-type
                       :area (p/make-field-fetcher (wrap-count-invocations fetch-area) :batch-fn batch-by-shape)}}))]
    (is (= {:invocations #{[fetch-shapes nil]
                           [fetch-area :rect #{{:shape :rect :width 20 :height 10}
                                               {:shape :rect :width 10 :height 20}}]
                           [fetch-area :square #{{:shape :square :width 15}}]}
            :result {:data {:shapes [{:type "rect"
                                      :area 200}
                                     {:type "rect"
                                      :area 200}
                                     {:type "square"
                                      :area 225}]}}}
           (with-invocation-counter
             (l/execute s "{shapes { type area }}" {} {}))))))