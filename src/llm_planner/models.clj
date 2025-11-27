(ns llm-planner.models)


(def _PlanInput
  [:map
   [:name          :string]
   [:context       :string]
   [:plan_state_id :string]])

(defn _create-plan
  [_connection _input])
