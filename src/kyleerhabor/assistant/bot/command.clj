(ns kyleerhabor.assistant.bot.command
  (:require
   [clojure.core.async :refer [<! go]]
   [clojure.java.io :as io]
   [clojure.set :refer [rename-keys]]
   [clojure.string :as str]
   [kyleerhabor.assistant.bot.util :refer [avatar-url user]]
   [kyleerhabor.assistant.bot.schema :refer [max-get-channel-messages-limit message-flags]]
   [kyleerhabor.assistant.config :refer [config]]
   [cprop.tools :refer [merge-maps]]
   [discljord.messaging :as msg]
   [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
   [com.rpl.specter :as sp])
  (:import
   (java.time Instant)
   (java.time.temporal ChronoUnit)))

;; From 16 to 4096
(def image-sizes (map #(long (Math/pow 2 %)) (range 4 13)))

(def max-image-size (last image-sizes))

(defn respond [inter data]
  [:create-interaction-response (merge
                                  {:id (:id inter)
                                   :token (:token inter)}
                                  data)])

(defn avatar [{inter :interaction}]
  (let [user (if-let [usero (:user (:options user))]
               (get (:users (:resolved (:data inter))) (:value usero))
               (user inter))
        size (or (:value (:size (:options (:data inter)))) max-image-size)
        attach? (:value (:attach (:options (:data inter))))
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

(defn purge [{inter :interaction}]
  (let [cid (:channel-id inter)
        amount (:value (:amount (:options (:data inter))))]
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

(defn choice [v]
  {:name (str v)
   :value v})

(def default-commands
  {:avatar {:handler avatar
            :name "avatar"
            :description "Displays a user's avatar."
            :options {:user {:name "user"
                             :type (:user command-option-types)
                             :description "The user to retrieve the avatar of, defaulting to whoever ran the command."}
                      :size {:name "size"
                             :type (:integer command-option-types)
                             :description (str
                                            "The largest size to return the avatar in. Actual avatar size will be lower"
                                            "if unavailable.")
                             ;; No, I'm not going to auto-generate the keys from a mere number. The keys are names for
                             ;; programmers (a separate space). I've dealt with worse cases of auto-generated names
                             ;; (GraphQL struct generator in Swift), and it's not fun. The prefixed "s" means size. It's
                             ;; possible to start a keyword with a number, but it's a bad practice with limited support:
                             ;; https://clojure.org/guides/faq#keyword_number
                             :choices (update-vals {:s16 16
                                                    :s32 32
                                                    :s64 64
                                                    :s128 128
                                                    :s256 256
                                                    :s512 512
                                                    :s1024 1024
                                                    :s2048 2048
                                                    :s4096 4096} choice)}
                      :attach {:name "attach"
                               :type (:boolean command-option-types)
                                            ;; The second sentence could be improved. After what updates? Also, to users
                                            ;; in the app, the first part may be confusing, since sending avatars as
                                            ;; links appear as attachments.
                               :description (str
                                              "Whether or not to send the avatar as an attachment. Useful for retaining"
                                              "avatars after updates.")}}}
   :purge {:handler purge
           :name "purge"
           :description "Deletes messages from a channel." ; Should it be "in" instead of "from"?
           :options {:amount {:name "amount"
                              :type (:integer command-option-types)
                              :description "The largest number of messages to delete. Actual amount may be lower."
                              :required? true
                              :min-value 1
                              :max-value 100}}}})

(def commands (merge-maps default-commands (::commands config)))

(defn commands-by-name [cmds]
  (reduce
    (fn [cmds [id cmd]]
      (let [cmd (update cmd :options
                  (fn index [opts]
                    (reduce
                      (fn [opts [id opt]]
                        (let [;; Less concise than using update, but won't leave empty maps everywhere.
                              opt (sp/multi-transform (sp/multi-path
                                                        [(sp/must :options) (sp/terminal index)]
                                                        [(sp/must :choices) (sp/terminal index)])
                                    opt)]
                          (assoc opts (:name opt) (assoc opt :id id)))) {} opts)))]
        (assoc cmds (:name cmd) (assoc cmd :id id))))
    {}
    cmds))

(def commands-named (commands-by-name commands))

(def sub? (set (map command-option-types [:sub-command :sub-command-group])))

(defn router [inter reg] ; Note that reg is a name resolver.
  (loop [opt (:data inter)
         reg reg
         path []]
    (let [name (:name opt)
          res {:path path
               :option opt
               :registry reg}]
      (if-let [item (get reg name)]
        (let [path (conj path (:id item))
              reg (:options item)
              ;; Note the difference in scoping.
              res (assoc res
                    :path path
                    :registry reg)]
          (if-let [nopt (first (:options opt))]
            (if (sub? (:type nopt))
              (recur nopt reg path)
              res)
            res))
        res))))

(defn route [router reg] ; Note that :reg is stored in router, while the reg param is a "true" commands map.
  ;; Update the interaction so its :options has its names resolved.
  {:registry (get-in reg (interpose :options (:path router)))
   :option (reduce (fn [m {:keys [name]
                           :as opt}]
                     (assoc m (:id (get (:registry router) name)) opt)) {} (:options (:option router)))})

(declare discord-option)

(defn apply-discord-options [opt desc]
  (sp/transform (sp/must :options)
    (fn [opts]
      (map
        (fn [{:keys [id]
              :as odesc}]
          (discord-option (id opts) odesc)) (:options desc))) opt))

(defn discord-option [opt desc]
  (let [opt (-> opt
              (select-keys [:type :name :description :required? :min-value :max-value :choices :options])
              (rename-keys {:required? :required
                            :min-value :min_value
                            :max-value :max_value}))
        opt (sp/transform (sp/must :choices)
              (fn [choices]
                (map
                  (fn [{:keys [id]}]
                    (id choices))
                  (:choices desc)))
              opt)]
    (apply-discord-options opt desc)))

(defn discord-command
  "Converts a command into a representation (map) compatible with Discord (for upload)."
  [cmd desc]
  (let [cmd* (select-keys cmd [:name :description :options])]
   (apply-discord-options cmd* desc)))

(def discord-commands (map
                         (fn [{:keys [id]
                               :as desc}]
                           (discord-command (id commands) desc))
                         [{:id :avatar
                           :options [{:id :user}
                                     {:id :size
                                      :choices [{:id :s16}
                                                {:id :s32}
                                                {:id :s64}
                                                {:id :s128}
                                                {:id :s256}
                                                {:id :s512}
                                                {:id :s1024}
                                                {:id :s2048}
                                                {:id :s4096}]}
                                     {:id :attach}]}
                          {:id :purge
                           :options [{:id :amount}]}]))

(defn upload
  ([conn] (upload conn discord-commands))
  ([conn cmds]
   (go
     (let [{:keys [id]} (<! (msg/get-current-application-information! conn))]
       (<! (msg/bulk-overwrite-global-application-commands! conn id cmds))))))