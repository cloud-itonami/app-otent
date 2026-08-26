(ns app-tenkyu.globe.renderer
  "Pick a backend, then speak to it through one vocabulary.

  WebGPU first, WebGL 2 second. That order is the workspace rule, and the
  reason it is a rule rather than a preference is that a capability check
  written per-app drifts: `navigator.gpu` existing is not the same as an
  adapter being available, and an adapter existing is not the same as
  `requestDevice` succeeding. All three are checked, in that order, before
  WebGPU is claimed.

  **Which backend ran is reported, never assumed.** `:backend` is in the
  state and the UI shows it, because a WebGPU build silently falling back
  to WebGL for a month is exactly the kind of thing nobody notices."
  (:require [app-tenkyu.globe.webgpu :as gpu]
            [app-tenkyu.globe.webgl :as gl]))

(defn create
  "Resolve to `{:backend :webgpu|:webgl2 ...}`, or to
  `{:backend :none :reason ...}` when neither is available.

  `:none` is a value, not a throw: a browser with no WebGL 2 is a fact
  the page should state, not an exception it should die of."
  [canvas]
  (-> (gpu/create canvas)
      (.then (fn [g]
               (or g
                   (or (gl/create canvas)
                       {:backend :none
                        :reason (if (exists? js/navigator.gpu)
                                  (str "WebGPU is present but no device could be acquired, "
                                       "and WebGL 2 is unavailable")
                                  "neither WebGPU nor WebGL 2 is available in this browser")}))))
      (.catch (fn [e]
                (or (gl/create canvas)
                    {:backend :none :reason (str "renderer setup failed: " (.-message e))})))))

(defn- dispatch [state]
  (case (:backend state)
    :webgpu {:ensure-tile! gpu/ensure-tile! :drop-tiles! gpu/drop-tiles!
             :set-lines! gpu/set-lines! :set-markers! gpu/set-markers!
             :set-buildings! gpu/set-buildings! :set-surface! gpu/set-surface!
             :draw! gpu/draw!}
    :webgl2 {:ensure-tile! gl/ensure-tile! :drop-tiles! gl/drop-tiles!
             :set-lines! gl/set-lines! :set-markers! gl/set-markers!
             :set-buildings! gl/set-buildings! :set-surface! gl/set-surface!
             :draw! gl/draw!}
    nil))

(defn ensure-tile! [state coord image segments]
  (when-let [d (dispatch state)] ((:ensure-tile! d) state coord image segments)))

(defn drop-tiles! [state keep-set]
  (when-let [d (dispatch state)] ((:drop-tiles! d) state keep-set)))

(defn set-lines! [state verts]
  (when-let [d (dispatch state)] ((:set-lines! d) state verts)))

(defn set-markers! [state verts]
  (when-let [d (dispatch state)] ((:set-markers! d) state verts)))

(defn set-buildings! [state mesh]
  (when-let [d (dispatch state)] ((:set-buildings! d) state mesh)))

(defn set-surface! [state mesh]
  (when-let [d (dispatch state)] ((:set-surface! d) state mesh)))

(defn draw! [state frame]
  (when-let [d (dispatch state)] ((:draw! d) state frame)))

(defn resize!
  "Match the drawing buffer to the CSS size times the device pixel ratio.

  Capped at 2. Above it a 4K display on a retina laptop asks for a 4x
  supersampled globe and the frame time triples for a difference nobody
  can see."
  [canvas]
  (let [dpr (min 2.0 (or js/window.devicePixelRatio 1.0))
        w (Math/round (* dpr (.-clientWidth canvas)))
        h (Math/round (* dpr (.-clientHeight canvas)))]
    (when (and (pos? w) (pos? h)
               (or (not= w (.-width canvas)) (not= h (.-height canvas))))
      (set! (.-width canvas) w)
      (set! (.-height canvas) h))
    {:dpr dpr :width (.-width canvas) :height (.-height canvas)
     :aspect (if (pos? (.-height canvas))
               (/ (.-width canvas) (.-height canvas))
               1.0)}))
