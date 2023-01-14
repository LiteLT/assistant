(ns kyleerhabor.assistant.bot.command
  (:require
   [clojure.core.async :refer [<! go]]
   [clojure.java.io :as io]
   [clojure.set :refer [rename-keys]]
   [clojure.string :as str]
   [kyleerhabor.assistant.bot.util :refer [avatar-url user]]
   [kyleerhabor.assistant.bot.schema :refer [max-get-channel-messages-limit message-flags]]
   [kyleerhabor.assistant.config :refer [config]]
   [discljord.messaging :as msg]
   [discljord.messaging.specs :refer [command-option-types interaction-response-types]])
  (:import
   (java.time Instant)
   (java.time.temporal ChronoUnit)))

;; From 16 to 4096
(def image-sizes (map #(long (Math/pow 2 %)) (range 4 13)))

(def max-image-size (last image-sizes))

(defn option [opt name]
  ;; The :options could be converted to a map prior and accessed as a get, but since most interactions will only list a
  ;; few options, it would likely be an array map, killing the performance benefits and only leaving the nice API look.
  (first (filter #(= name (:name %)) (:options opt))))

(defn respond [inter data]
  [:create-interaction-response (merge
                                  {:id (:id inter)
                                   :token (:token inter)}
                                  data)])

(defn avatar [{{:keys [data]
                :as inter} :interaction}]
  (let [user (if-let [opt (option data "user")]
               (get (:users (:resolved data)) (:value opt))
               (user inter))
        size (or (:value (option data "size")) max-image-size)
        attach? (:value (option data "attach"))
        url (avatar-url user size)]
    (if attach?
      [(respond inter
         {:type (:deferred-channel-message-with-source interaction-response-types)
          :handler (fn [_]
                     (let [path (.getPath (io/as-url url))
                           filename (subs path (inc (str/last-index-of path \/)))]
                       ;; TODO: Add error handling for when attaching the image would be too large. I tried with a
                       ;; followup in a followup, but that didn't work. :(
                       [[:create-followup-message {:app-id (:application-id inter)
                                                   :token (:token inter)
                                                   :opts {:stream {:content url
                                                                   :filename filename}}}]]))})]
      [(respond inter {:type (:channel-message-with-source interaction-response-types)
                       :opts {:data {:content url}}})])))

(defn purge-result [inter n]
  (respond inter
    {:type (:channel-message-with-source interaction-response-types)
     :opts {:data {:content (case n
                              0 "Could not delete messages."
                              1 "Deleted 1 message."
                              (str "Deleted " n " messages."))
                   :flags (:ephemeral message-flags)}}}))

(defn purge [{{cid :channel-id
               :as inter} :interaction}]
  (let [amount (:value (option (:data inter) "amount"))]
    [[:get-channel-messages
      {:channel-id cid
       ;; The filter may return less than the requested amount. To alleviate this burden for the user we're going to
       ;; fetch the maximum amount of messages in a single request, run the filters, then take the requested amount.
       ;; Note that the result could still return less than desired.
       :opts {:limit max-get-channel-messages-limit}
       :handler (fn [msgs]
                  ;; Does time manipulation *really* have anything to do with my problem?
                  (let [old (.minus (Instant/now) 14 ChronoUnit/DAYS)
                        ids (->> msgs
                              (filter #(not (:pinned %)))
                              ;; Flawed. It should be measuring Discord's perception of time (i.e. not mine).
                              (filter #(.isAfter (Instant/parse (:timestamp %)) old))
                              (take amount)
                              (map :id))
                        n (count ids)
                        deleted-response (fn [success?]
                                           [(purge-result inter (if success? n 0))])]
                    (case n
                      0 [(respond inter
                           {:type (:channel-message-with-source interaction-response-types)
                            :opts {:data {:content "No messages to delete."
                                          :flags (:ephemeral message-flags)}}})]
                      ;; Bulk deletes require at least two messages.
                      1 [[:delete-message
                          {:channel-id cid
                           :message-id (first ids)
                           :handler deleted-response}]]
                      [[:bulk-delete-messages
                        {:channel-id cid
                         :msg-ids ids
                         :handler deleted-response}]])))}]]))

;; Straight up broken. The string keys represent the Discord form of command names, which is meant to be configurable.
;; This, however, treats it as static so a command can efficiently be navigated to.
(def commands {"avatar" {:id :avatar
                         :handler avatar}
               "purge" {:id :purge
                        :handler purge}})

(def sub? (set (map command-option-types [:sub-command :sub-command-group])))

(defn route [inter]
  (loop [opt (:data inter)
         path [(:name opt)]]
    (let [opt* (first (:options opt))]
      (if (sub? (:type opt*))
        (recur opt* (conj path (:name opt*)))
        {:path path
         :option opt}))))

(def discord-commands*
  [{:id :avatar
    :name "avatar"
    :options [{:id :user
               :type (:user command-option-types)
               :name "user"}
              {:id :size
               :type (:integer command-option-types)
               :name "size"
               :choices (map #(zipmap [:name :value] [(str %) %]) image-sizes)}
              {:id :attach
               :type (:boolean command-option-types)
               :name "attach"}]}
   {:id :purge
    :name "purge"
    :options [{:id :amount
               :type (:integer command-option-types)
               :name "amount"
               :required? true
               :min-value 1
               :max-value 100}]}])

(defn process-discord-command [cmd config]
  (let [cmd* (-> (merge (dissoc cmd :id) (select-keys config [:name :name-localizations
                                                              :description :description-localizations]))
               (rename-keys {:name-localizations :name_localizations
                             :description-localizations :description_localizations
                             :required? :required
                             :dms? :dm_permission
                             :min-value :min_value
                             :max-value :max_value}))]
    (if (:options cmd*)
      (update cmd* :options (partial map #(process-discord-command % ((:id %) (:options config)))))
      cmd*)))

(def discord-commands (map #(process-discord-command % ((:id %) (::commands config))) discord-commands*))

(defn upload
  ([conn] (upload conn discord-commands))
  ([conn cmds]
   (go
     (let [{:keys [id]} (<! (msg/get-current-application-information! conn))]
       (<! (msg/bulk-overwrite-global-application-commands! conn id cmds))))))
