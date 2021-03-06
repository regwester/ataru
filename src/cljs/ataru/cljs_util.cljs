(ns ataru.cljs-util
  (:require [clojure.string :refer [join]]
            [cljs.core.match :refer-macros [match]]
            [cljs.reader :as reader :refer [read-string]]
            [cljs-uuid-utils.core :as uuid]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [taoensso.timbre :refer-macros [spy debug]]
            [cemerick.url :as url]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [goog.string.format]
            [ataru.translations.translation-util :as translation-util]
            [goog.string :as gstring]
            [goog.string.format])
  (:import [goog.net Cookies]))

(def wrap-scroll-to
  (with-meta identity {:component-did-mount #(let [node (r/dom-node %)]
                                              (if (.-scrollIntoViewIfNeeded node)
                                                (.scrollIntoViewIfNeeded node)
                                                (.scrollIntoView node)))}))


(defn debounce
  ([f] (debounce f 1000))
  ([f timeout]
   (let [id (atom nil)]
     (fn [& args]
       (if (not (nil? @id))
         (js/clearTimeout @id))
       (reset! id (js/setTimeout
                    (apply partial f args)
                    timeout))))))

(defn dispatch-after-state
  [& {:keys [predicate handler]}]
  {:pre [(not (nil? predicate))
         (not (nil? handler))]}
  (let [handler-ref (atom nil)
        sanity-count (atom 0)
        dispatcher (fn [db]
                     (match [(swap! sanity-count inc) (predicate db)]
                            [50 _] (js/clearInterval @handler-ref)
                            [_ (result :guard (comp true? boolean))]
                            (do
                              (js/clearInterval @handler-ref)
                              (handler result))
                            :else nil))]
    (reset!
      handler-ref
      (js/setInterval
        #(dispatch [:state-update dispatcher])
        200))))

(defn set-global-error-handler!
  "Sets the global error handler. Prints stack trace of uncaught
   error"
  [send-to-server-fn]
  (set! (.-onerror js/window)
        (fn [error-msg url line col error-obj]
          (let [user-agent (-> js/window .-navigator .-userAgent)
                error-details {:error-message error-msg
                               :url url
                               :line line
                               :col col
                               :user-agent user-agent}]
            (-> ((.-fromError js/StackTrace) error-obj)
                (.then (fn [frames]
                         (->> (for [frame frames]
                                (.toString frame))
                              (interpose "\n")
                              (apply str)
                              (assoc error-details :stack)
                              (send-to-server-fn)))))))))

(defn cljs->str
  [data]
  (str data))

(defn str->cljs
  [str]
  (reader/read-string str))

(defn new-uuid []
  (uuid/uuid-string (uuid/make-random-uuid)))

(defn get-path []
  (.. js/window -location -pathname))

(def ^:private ->kebab-case-kw (partial transform-keys ->kebab-case-keyword))

(defn extract-query-params
  "Returns query params as map with keywordized keys
   ?param=foo&biz=42 -> {:param \"foo\" :biz \"42\"}"
  []
  (-> (.. js/window -location -href)
      (url/url)
      (:query)
      (->kebab-case-kw)))

(defn remove-empty-query-params
  [params]
  (into {} (remove #(-> % second empty?)) params))

(defn- update-query-params
  [url params]
  (let [new-params (-> (:query url)
                       (clojure.walk/keywordize-keys)
                       (merge params)
                       (remove-empty-query-params))]
    (assoc url :query new-params)))

(defn update-url-with-query-params
  [params]
  (let [url (-> (.. js/window -location -href)
                (url/url)
                (update-query-params params)
                (str))]
    (.replaceState js/history nil nil url)))

(defn set-query-param
  [key value]
  (let [new-url (-> (.. js/window -location -href)
                    (url/url)
                    (assoc-in [:query key] value)
                    str)]
    (.replaceState js/history nil nil new-url)))

(defn unset-query-param
  [key]
  (let [new-url (-> (.. js/window -location -href)
                    (url/url)
                    (update :query dissoc key)
                    str)]
    (.replaceState js/history nil nil new-url)))

(defn get-unselected-review-states
  [unselected-states all-states]
  (clojure.set/difference
    (->> all-states
         (map first)
         set)
    (set unselected-states)))

(defn include-csrf-header? [method]
  (contains? #{:post :put :delete} method))

(defn csrf-token []
  (when-let [token (-> js/document
                  Cookies.
                  (.get "CSRF"))]
    (js/decodeURIComponent token)))

(defn flatten-path [db & parts]
  (flatten [:editor :forms (-> db :editor :selected-form-key) :content [parts]]))

(defn- resize-vector [target-length x]
  (let [add-length (- target-length (count x))]
    (cond-> x
      (> add-length 0)
      (into (repeatedly add-length (fn [] nil))))))

(defn vector-of-length [target-length]
  (comp (partial resize-vector target-length)
        (fnil identity [])))

(defn get-translation [key & params]
  (if (some? params)
    (apply gstring/format
      (translation-util/get-translation key @(subscribe [:application/form-language]))
      params)
    (translation-util/get-translation key @(subscribe [:application/form-language]))))

(defn modify-event? [event]
  (some #{(:event-type event)} ["updated-by-applicant" "updated-by-virkailija"]))
