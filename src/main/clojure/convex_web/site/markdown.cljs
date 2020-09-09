(ns convex-web.site.markdown
  (:require [convex-web.site.gui :as gui]
            [convex-web.site.backend :as backend]))

(defn get-on-push
  "Returns a function to get markdown from the server.

   Markdown state will be stored under the key `:markdown`."
  [markdown-key]
  (fn [_ _ set-state]
    (set-state update :markdown assoc :ajax/status :ajax.status/pending)

    (backend/GET-markdown-page
      markdown-key
      {:handler
       (fn [markdown-page]
         (set-state update :markdown merge {:ajax/status :ajax.status/success} markdown-page))

       :error-handler
       (fn [error]
         (set-state update :markdown assoc :ajax/status :ajax.status/error :ajax/error error))})))

(defn Markdown [{:keys [markdown]}]
  (let [{:keys [ajax/status contents toc?] :or {toc? true}} markdown]
    [:div.flex.flex-1.overflow-auto
     (case status
       :ajax.status/pending
       [:div.flex.flex-1.items-center.justify-center
        [gui/Spinner]]

       :ajax.status/error
       [:span "Error"]

       :ajax.status/success
       [:<>
        ;; -- Markdown
        [:div.overflow-auto
         {:class
          (if toc?
            ;; Add right padding to have some spacing between the markdown and the TOC.
            "pr-10"
            ;; Without a TOC the markdown must have a full width.
            "flex-1")}

         (for [{:keys [name content]} contents]
           ^{:key name}
           [:article.prose.mb-10
            {:id name}
            [gui/Markdown content]])]

        ;; -- On this page
        (when toc?
          [:div.flex.flex-col.ml-10
           [:span.text-xs.text-gray-500.font-bold.uppercase "On this Page"]

           [:ul.list-none.text-sm.mt-4
            (for [{:keys [name]} contents]
              ^{:key name}
              [:li.mb-2
               [:a.text-gray-600.hover:text-gray-900.cursor-pointer
                {:on-click #(gui/scroll-into-view name)}
                name]])]])]

       [:div])]))
