(ns convex-web.site.repl
  (:require [convex-web.site.gui :as gui]
            [convex-web.site.command :as command]
            [convex-web.site.session :as session]
            [convex-web.site.stack :as stack]
            [convex-web.site.backend :as backend]
            [convex-web.site.format :as format]

            [clojure.string :as str]
            [cljs.spec.alpha :as s]

            [codemirror-reagent.core :as codemirror]
            [reagent.core :as reagent]
            [reitit.frontend.easy :as rfe]
            [zprint.core :as zprint]
            
            ["react-resizable" :refer [ResizableBox]]))

(defn mode [state]
  (:convex-web.repl/mode state))

(defn language [state]
  (:convex-web.repl/language state))

(defn commands
  "Returns a collection of REPL Commands sorted by ID."
  [state]
  (:convex-web.repl/commands state))

(defn command-source [{:convex-web.command/keys [query transaction]}]
  (or (get query :convex-web.query/source)
      (get transaction :convex-web.transaction/source)))

(defn query?
  "Query Command?"
  [command]
  (= :convex-web.command.mode/query (:convex-web.command/mode command)))

(defn transaction?
  "Transaction Command?"
  [command]
  (= :convex-web.command.mode/transaction (:convex-web.command/mode command)))

(defn selected-tab [state]
  (get-in state [:convex-web.repl/sidebar :sidebar/tab] :reference))

(defn sidebar-open? [state]
  (get-in state [:convex-web.repl/sidebar :sidebar/open?] false))

(defn toggle-sidebar [set-state]
  (set-state update-in [:convex-web.repl/sidebar :sidebar/open?] not))

;; ---

(def convex-lisp-examples
  (let [make-example (fn [& examples]
                       (str/join "\n\n" examples))]
    [["Self Balance"
      (make-example "*balance*")]

     ["Self Address"
      (make-example "*address*")]

     ["Check Balance"
      (make-example
        "(balance #9)")]

     ["Transfer"
      (make-example
        "(transfer #9 1000)")]

     ["Creating a Token"
      (make-example
        "(import convex.fungible :as fungible)"
        "(def my-token (deploy (fungible/build-token {:supply 1000})))")]

     ["Simple Storage Actor"
      (make-example
        "(def storage-example-address (deploy '(do (def stored-data nil) (defn get [] stored-data) (defn set [x] (def stored-data x)) (export get set))))")]

     ["Call Actor"
      (make-example
        "(def storage-example-address (deploy '(do (def stored-data nil) (defn get [] stored-data) (defn set [x] (def stored-data x)) (export get set))))"

        "(call storage-example-address (set 1))"
        "(call storage-example-address (get))")]

     ["Subcurrency Actor"
      (make-example
        "(deploy '(do (def owner *caller*) (defn contract-transfer [receiver amount] (assert (= owner *caller*)) (transfer receiver amount)) (defn contract-balance [] *balance*) (export contract-transfer contract-balance)))")]]))

(defn Examples [language]
  (let [Title (fn [title]
                [:span.text-sm title])]
    
    [:div.flex.flex-col.flex-1.pl-1.pr-4.overflow-auto
     (map
       (fn [[title source-code]]
         (let [source-code (try
                             (zprint/zprint-str source-code {:parse-string? true :width 60})
                             (catch js/Error _
                               source-code))]
           ^{:key title}
           [:div.flex.flex-col.py-2
            [:div.flex.justify-between.items-center
             [Title title]
             [gui/ClipboardCopy source-code]]
            
            [gui/Highlight source-code {:language language}]]))
       convex-lisp-examples)]))

(defn Reference [reference]
  (reagent/with-let [search-string-ref (reagent/atom nil)
                     selected-library-ref (reagent/atom "convex.core")]
    (let [libraries (keys reference)
          
          search-string @search-string-ref
          
          selected-library @selected-library-ref
          
          reference (get reference selected-library)
          
          filtered-reference (if (str/blank? search-string)
                               reference
                               (filter
                                 (fn [[sym _]]
                                   (str/includes? (name sym) search-string))
                                 reference))
          
          filtered-reference (sort-by first filtered-reference)]
      
      [:div.h-full.flex.flex-col.p-1.space-y-3.overflow-auto
       
       ;; Select library.
       [:div.flex.items-center.space-x-3
        [:span.font-mono.text-sm.text-gray-500
         "Library"]
        
        [gui/Select2
         {:selected selected-library
          :options
          (map 
            (fn [library-name]
              {:id library-name
               :value library-name})
            libraries)
          :on-change #(reset! selected-library-ref %)}]]
       
       [:input.text-sm.font-mono.mb-2.px-1.border.rounded
        {:type "text"
         :placeholder "Search"
         :value (or search-string "")
         :on-change #(reset! search-string-ref (gui/event-target-value %))}]
       
       [:div.h-full.flex.flex-col.space-y-2.overflow-auto
        (for [[sym metadata] filtered-reference]
          ^{:key sym}
          [:<>
           [gui/SymbolMeta
            {:library selected-library
             :symbol sym
             :metadata metadata}]
           
           [:hr.my-2]])]])))

(defn Input [state set-state]
  ;; `source-ref` is a regular Atom
  ;; because the component doesn't need
  ;; to update when the Atom's value changes.
  (reagent/with-let [editor-ref (atom nil)
                     source-ref (atom "")
                     history-index (atom nil)]
    (let [active-address (session/?active-address)
          
          execute (fn []
                    (when-let [editor @editor-ref]
                      ;; Reset history navigation index.
                      (reset! history-index nil)
                      
                      (let [source (codemirror/cm-get-value editor)
                            
                            transaction #:convex-web.transaction {:type :convex-web.transaction.type/invoke
                                                                  :source source
                                                                  :language (language state)}
                            
                            query #:convex-web.query {:source source
                                                      :language (language state)}
                            
                            command (merge #:convex-web.command {:mode (mode state)}
                                      (case (mode state)
                                        :convex-web.command.mode/query
                                        (merge #:convex-web.command {:query query}
                                          ;; Address is optional in query mode.
                                          (when active-address
                                            #:convex-web.command {:address active-address}))
                                        
                                        :convex-web.command.mode/transaction
                                        #:convex-web.command {:address active-address
                                                              :transaction transaction}))]
                        
                        (when-not (str/blank? (codemirror/cm-get-value editor))
                          (codemirror/cm-set-value editor "")
                          
                          (command/execute command (fn [command-previous-state command-new-state]
                                                     (set-state
                                                       (fn [state]
                                                         (let [{:convex-web.command/keys [id] :as command'} (merge command-previous-state command-new-state)
                                                               
                                                               commands (or (commands state) [])
                                                               
                                                               ;; Without checking for the ID a Command without an ID
                                                               ;; would be flagged since both values are nil.
                                                               should-update? (when id
                                                                                (some
                                                                                  (fn [{this-id :convex-web.command/id}]
                                                                                    (= id this-id))
                                                                                  commands))
                                                               
                                                               commands' (if should-update?
                                                                           ;; Map over the existing Commands to update the matching one.
                                                                           (mapv
                                                                             (fn [{this-id :convex-web.command/id :as command}]
                                                                               (if (= id this-id)
                                                                                 (merge command command')
                                                                                 command))
                                                                             commands)
                                                                           ;; Don't need to update so we simply add the Command to the list.
                                                                           (conj commands command'))]
                                                           
                                                           (assoc state :convex-web.repl/commands commands'))))))))))]
      
      ;; Don't allow queries or transactions without an account.
      (if (nil? active-address)
        ;; Without an active Account
        [:div.bg-gray-200.rounded.flex.items-center.justify-center
         ;; Height must match resizable box (see below).
         {:style
          {:height "120px"}}
         [gui/Tooltip
          {:title "Creat an account to get started"
           :size "small"}
          [gui/DefaultButton
           {:on-click #(stack/push :page.id/create-account {:modal? true})}
           [:span.text-xs.uppercase "Create Account"]]]]
        
        ;; With an active Account
        [:> ResizableBox
         {:width "100%"
          :height 120
          :axis "y"
          :handle
          (reagent/as-element 
            [:div.absolute.inset-x-0.top-0.flex.justify-center.text-gray-500
             {:class "-my-2"
              :style
              {:cursor "row-resize"}}
             [:svg {:class "h-5 w-5" :viewBox"0 0 20 20" :fill"currentColor"}
              [:path {:fill-rule "evenodd", :d "M3 7a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM3 13a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z", :clip-rule "evenodd"}]]])
          :resizeHandles #js ["n"]
          :onResizeStop
          (fn [_ _]
            ;; Move focus to editor.
            (codemirror/cm-focus @editor-ref))}
         
         ;; Pretending to be Codemirror.
         ;; This div's height is bigger than Codemirror's, so we change its cursor to text
         ;; to pretend to be the editor and focus Codemirror on click.
         [:div.h-full.flex.mt-2.space-x-1.cursor-text
          {:on-click #(codemirror/cm-focus @editor-ref)}
          (let [enter-extra-key 
                (fn []
                  (if-let [editor @editor-ref]
                    (let [^js pos (-> editor
                                    (codemirror/cm-get-doc)
                                    (codemirror/cm-get-cursor))
                          
                          last-line (-> editor
                                      (codemirror/cm-get-doc)
                                      (codemirror/cm-last-line))
                          
                          line (-> editor
                                 (codemirror/cm-get-doc)
                                 (codemirror/cm-get-line last-line))
                          
                          last-line? (= last-line (.-line pos))
                          last-ch? (= (count line) (.-ch pos))]
                      
                      (if (and last-line? last-ch?)
                        (execute)
                        codemirror/pass))
                    codemirror/pass))
                
                history-up (fn [cm]
                             (let [c (vec (commands state))
                                   ;; Do nothing if there are no Commands.
                                   i (or @history-index (some-> (seq c) count))
                                   ;; Max '0' to fallback to first Command.
                                   i (some-> i (dec) (max 0))]
                               (when i
                                 (codemirror/cm-set-value cm (command-source (get c i)))
                                 (codemirror/set-cursor-at-the-end cm)
                                 
                                 (reset! history-index i))))
                
                history-down (fn [cm]
                               (let [c (vec (commands state))
                                     ;; Do nothing if there's no index set.
                                     ;; Min 'count' to fallback to last Command.
                                     i (some-> @history-index (inc) (min (dec (count c))))]
                                 (when i
                                   (codemirror/cm-set-value cm (command-source (get c i)))
                                   (codemirror/set-cursor-at-the-end cm)
                                   
                                   (reset! history-index i))))
                
                clear-all (fn [cm]
                            (codemirror/cm-set-value cm ""))]
            [codemirror/CodeMirror
             [:div.relative.flex-shrink-0.flex-1.overflow-auto.border.rounded]
             {:configuration {:lineNumbers false
                              :value @source-ref
                              :mode (case (language state)
                                      :convex-lisp
                                      "clojure"
                                      
                                      "clojure")}
              
              :on-mount (fn [_ editor]
                          (->> (codemirror/extra-keys {:enter enter-extra-key
                                                       :shift-enter execute
                                                       :ctrl-up history-up
                                                       :ctrl-down history-down
                                                       :ctrl-backspace clear-all})
                            (codemirror/set-extra-keys editor))
                          
                          (reset! editor-ref editor)
                          
                          (codemirror/cm-focus editor))
              :on-update (fn [_ editor]
                           (->> (codemirror/extra-keys {:enter enter-extra-key
                                                        :shift-enter execute
                                                        :ctrl-up history-up
                                                        :ctrl-down history-down
                                                        :ctrl-backspace clear-all})
                             (codemirror/set-extra-keys editor))
                           
                           (codemirror/cm-focus editor))
              
              :events {:editor {"change" (fn [editor _]
                                           (reset! source-ref (codemirror/cm-get-value editor)))
                                
                                ;; -- Example of format on paste.
                                ;;"paste" (fn [editor event]
                                ;;          ;; Convex Lisp source if formated on paste.
                                ;;          (when (= :convex-lisp (language state))
                                ;;            (let [source (.getData (.-clipboardData event) "Text")
                                ;;                  source-pretty (zprint/zprint-str source {:parse-string? true})]
                                ;;              (codemirror/cm-set-value editor source-pretty)
                                ;;
                                ;;              (.preventDefault event))))
                                
                                }}}])
          
          [gui/Tooltip
           {:title "Run"
            :size "small"}
           [:button.flex.flex-col.justify-center.h-full
            {:class 
             ["px-2 py-1"
              "rounded"
              "bg-gray-100"
              "hover:bg-gray-200 hover:shadow"
              "active:bg-gray-300"]
             :on-click execute}
            [gui/PlayIcon
             {:class
              ["w-6 h-6"
               "text-green-500"
               "rounded-full"
               "cursor-pointer"]}]]]]]))))

(def output-symbol-metadata-options
  {:show-examples? false})

(defn error-code-string [code]
  (cond
    (string? code)
    code

    (keyword? code)
    (name code)

    :else
    (str code)))

(defmulti error-message :code)

(defmethod error-message :default
  [{:keys [code message status]}]
  (cond
    (= status 403)
    "The sandbox has been updated! Please refresh your browser."
    
    code
    (str (error-code-string code)
      (when message
        (str ": " message)))
    
    :else
    "Unknown error"))

(defmethod error-message :STATE
  [{:keys [message]}]
  message)

(defmethod error-message :UNDECLARED
  [{:keys [message]}]
  (str "'" message "' is undeclared."))

(defmethod error-message :CAST
  [{:keys [message]}]
  (str "Cast error: " message "."))

(defn ErrorOutput [{:convex-web.command/keys [error]}]
  [:div.flex.flex-col.space-y-3
   [:code.text-xs.text-red-500
    (error-message error)]

   (when-let [trace (seq (:trace error))]
     [:div.flex.flex-col.space-y-1
      [:span.text-xs.uppercase.text-gray-600
       "Trace"]

      (for [t trace]
        ^{:key t}
        [:code.text-xs t])])])

(defn Commands [commands]
  [:div.w-full.h-full.max-w-full.overflow-auto.bg-gray-100.border.rounded
   (for [{:convex-web.command/keys [id status query transaction] :as command} commands]
     ;; Error Commands don't have an ID.
     ^{:key (or id (str (random-uuid)))}
     [:div.w-full.border-b.p-4.transition-colors.duration-500.ease-in-out
      {:ref
       (fn [el]
         (when el
           (.scrollIntoView el #js {"behavior" "smooth"
                                    "block" "center"})))
       :class
       (case status
         :convex-web.command.status/running
         "bg-yellow-100"
         :convex-web.command.status/success
         ""
         :convex-web.command.status/error
         "bg-red-100"
         
         "")}
      
      ;; -- Input
      [:div.flex.flex-col.items-start
       [:span.text-xs.uppercase.text-gray-600.block.mb-1
        "Source"]
       
       (let [source (or (get query :convex-web.query/source)
                      (get transaction :convex-web.transaction/source))]
         [:div.flex.items-center
          [gui/Highlight source {:pretty? true}]
          
          ;; This causes a strange overflow.
          #_[gui/ClipboardCopy source {:margin "ml-2"}]])]
      
      [:div.my-3]
      
      ;; -- Output
      [:div.flex.flex-col
       (let [error? (= :convex-web.command.status/error (get command :convex-web.command/status))]
         [:div.flex.mb-1
          [:span.text-xs.uppercase.text-gray-600
           (cond
             error?
             (let [code (get-in command [:convex-web.command/error :code])]
               (apply str (if (keyword? code)
                            ["Error " (str "(" (error-code-string code) ")")]
                            ["Unrecognised Non-Keyword Error Code"])))
             
             :else
             "Result")]
          
          ;; Don't display result type for errors.
          (when-not error?
            (when-let [type (get-in command [:convex-web.command/result :convex-web.result/type])]
              [gui/Tooltip
               {:title (str/capitalize type)
                :size "small"}
               [gui/InformationCircleIcon {:class "w-4 h-4 text-black ml-1"}]]))])
       
       [:div.flex
        (case status
          :convex-web.command.status/running
          [gui/SpinnerSmall]
          
          :convex-web.command.status/success
          [gui/ResultRenderer (:convex-web.command/result command)]
          
          :convex-web.command.status/error
          [ErrorOutput command])]]])])

;; --

(defn SandboxPage [_ {:convex-web.repl/keys [reference] :as state} _]
  (let [active-address (session/?active-address)
        
        ;; It's better if we store our REPL's state somewhere in the db
        ;; that it isn't ephemeral as the frame's state - since we want
        ;; to keep the state between page changes.
        ;; So, we do a simple trick: we create a new version of `state` and `set-state`.
        ;; Since the the new version of `state` and `set-state` has the same interface,
        ;; nothing has to change in any call-site.
        ;;
        ;; Note that REPL's state is per address.
        state (merge state (get-in (session/?state) [:page.id/repl active-address]))
        set-state (fn [f & args]
                    ;; Session's state is shared, so we need to be careful to
                    ;; update only the state of the REPL (& address).
                    (session/set-state (fn [state]
                                         (update-in state [:page.id/repl active-address] (fn [repl-state]
                                                                                           (apply f repl-state args))))))]
    [:div.flex.flex-1.space-x-8.overflow-auto
     
     ;; -- REPL
     [:div.w-screen.max-w-full.flex.flex-col.mb-6.space-y-2
      
      [:div.flex.justify-end
       [gui/Tooltip
        {:title "Show Examples & Reference"
         :size "small"}
        [gui/DefaultButton
         {:on-click #(toggle-sidebar set-state)}
         [gui/MenuAlt3Icon
          {:class "h-5 w-5"}]]]]
      
      ;; -- Output
      [Commands (commands state)]
      
      ;; -- Input
      [Input state set-state]
      
      ;; -- Help
      [:div.flex.space-x-2.pt-1.pb-4.items-center.justify-between
       
       ;; Shift return to run
       [:div.flex.space-x-2.text-gray-500
        [:span.text-xs "Press " [:code.font-bold "Shift+Return"] " to run."]
        
        ;; Keymaps
        [gui/Tooltip
         {:html
          (reagent/as-element
            [:div.flex.flex-col.text-xs.font-mono.space-y-1
             [:span.font-bold.text-sm.mb-2 "Keymaps"]
             
             [:div.flex.items-center.space-x-2
              [:span.font-bold "Run: "]
              [:span.bg-gray-500.p-1.rounded-md "Shift+Return"]]
             
             [:div.flex.items-center.space-x-2
              [:span.font-bold "Clear: "]
              [:span.bg-gray-500.p-1.rounded-md "Ctrl+Backspace"]]
             
             [:div.flex.items-center.space-x-2
              [:span.font-bold "Navigate history up: "]
              [:span.bg-gray-500.p-1.rounded-md "Ctrl+Up"]]
             
             [:div.flex.items-center.space-x-2
              [:span.font-bold "Navigate history down: "]
              [:span.bg-gray-500.p-1.rounded-md "Ctrl+Down"]]])}
         
         [gui/QuestionMarkCircle {:class "h-4 w-4"}]]]
       
       ;; Learn more
       [gui/Tooltip
        {:title "Sandbox Tutorial"
         :size "small"}
        [:a.text-xs.text-gray-500.rounded.hover:shadow.px-2.py-1.hover:bg-gray-100
         {:href "sandbox/tutorial"}
         "LEARN MORE"]]]
      
      ;; -- Options
      [:div.flex.flex-col.space-y-4.mt-1.cursor-default
       
       [:div.flex.space-x-6
        [:div.flex.items-center
         [:span.text-xs.text-gray-700.mr-1
          "Account"]
         [gui/AIdenticon {:value active-address :size gui/identicon-size-small}]
         [:a.hover:underline.hover:text-blue-500
          {:href (rfe/href :route-name/testnet.account {:address active-address})}
          [:span.font-mono.text-xs.block.ml-1
           (format/prefix-# active-address)]]]
        
        ;; -- Mode
        [:div.flex.items-center
         
         [:span.text-xs.text-gray-700.mr-1
          "Mode"]
         
         [:div.flex.items-center.space-x-1
          [gui/Select2
           {:selected (mode state)
            :options
            [{:id :convex-web.command.mode/transaction
              :value "Transaction"}
             {:id :convex-web.command.mode/query
              :value "Query"}]
            :on-change #(set-state assoc :convex-web.repl/mode %)}]
          
          [gui/InfoTooltip "Select \"Transaction\" to execute code as a transaction on the Convex Network. Select \"Query\" to execute code just to compute the result (No on-chain effects will be applied)."]]]]]]
     
     ;; -- Sidebar
     [gui/SlideOver
      {:open? (sidebar-open? state)
       :on-close #(toggle-sidebar set-state)}
      (let [selected-tab (selected-tab state)]
        [:div.flex.flex-col.h-full
         
         ;; -- Tabs
         [:div.flex.mb-5.space-x-4
          
          ;; -- Reference Tab
          [:span.text-sm.font-bold.leading-none.uppercase.p-1.cursor-pointer
           {:class
            (if (= :reference selected-tab)
              "border-b border-indigo-500"
              "text-black text-opacity-50")
            :on-click #(set-state assoc-in [:convex-web.repl/sidebar :sidebar/tab] :reference)}
           "Reference"]
          
          ;; -- Examples Tab
          [:span.text-sm.font-bold.leading-none.uppercase.p-1.cursor-pointer
           {:class
            (if (= :examples selected-tab)
              "border-b border-indigo-500"
              "text-black text-opacity-50")
            :on-click #(set-state assoc-in [:convex-web.repl/sidebar :sidebar/tab] :examples)}
           "Examples"]]
         
         (case selected-tab
           :examples
           [Examples (language state)]
           
           :reference
           [Reference reference])])]]))

(def sandbox-page
  #:page {:id :page.id/repl
          :description "Execute transactions live on the current test network. Fast and interactive."
          :initial-state
          {:convex-web.repl/language :convex-lisp
           :convex-web.repl/mode :convex-web.command.mode/transaction
           :convex-web.repl/sidebar {:sidebar/tab :examples}}
          :state-spec (s/keys :req [:convex-web.repl/mode] :opt [:convex-web.repl/commands])
          :component #'SandboxPage
          :on-push
          (fn [_ _ set-state]
            (backend/GET-reference
              {:handler
               (fn [reference]
                 (set-state assoc :convex-web.repl/reference reference))}))})


