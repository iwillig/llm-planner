(ns llm-planner.models)


(def PlanInput
  [:map
   [:name          :string]
   [:context       :string]
   [:plan_state_id :string]])

(defn create-plan
  [connection input])
