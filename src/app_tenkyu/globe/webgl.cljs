(ns app-tenkyu.globe.webgl
  "The WebGL 2 backend: the fallback, and the one most machines will use.

  It draws exactly what `app-tenkyu.globe.webgpu` draws, from exactly the
  same vertex data out of `app-tenkyu.globe.scene`. Nothing about where
  anything *is* lives here -- this file is buffers, shaders and draw
  calls, and that boundary is what makes 'the fallback shows the same
  scene' checkable rather than hopeful.

  Three passes, in this order and for this reason:

  1. **tiles** -- textured sphere patches, depth write on
  2. **lines**  -- coastlines and borders, lifted slightly, depth test on
  3. **markers** -- point sprites, depth test on, **blended**

  Markers last and blended so an aircraft on the far side of the planet is
  hidden by the planet rather than drawn through it. Depth test without
  depth write on the markers, so two markers at the same place blend
  instead of one punching a hole in the other.

  ## GLSL ES 3.00, and depth is 0..1 like WebGPU's

  `scene/perspective` builds a zero-to-one depth matrix because that is
  what WebGPU requires. WebGL's fixed function expects -1..1, so the
  difference is corrected once, in the vertex shader
  (`gl_Position.z = 2z - w`), rather than by keeping two matrices. One
  matrix means one thing to get wrong."
  (:require [app-tenkyu.globe.scene :as scene]
            [kotoba.geo.mesh :as mesh]))

(def ^:private tile-vs "#version 300 es
precision highp float;
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_norm;
layout(location=2) in vec2 a_uv;
uniform mat4 u_view_proj;
out vec2 v_uv;
out vec3 v_norm;
void main() {
  v_uv = a_uv;
  v_norm = a_norm;
  vec4 p = u_view_proj * vec4(a_pos, 1.0);
  // 0..1 depth (WebGPU convention) -> WebGL's -1..1.
  p.z = 2.0 * p.z - p.w;
  gl_Position = p;
}")

(def ^:private tile-fs "#version 300 es
precision highp float;
in vec2 v_uv;
in vec3 v_norm;
uniform sampler2D u_tex;
uniform vec3 u_sun;
out vec4 outColor;
void main() {
  vec3 c = texture(u_tex, v_uv).rgb;
  // A fixed key light so the sphere reads as a sphere. Not a day/night
  // terminator: that would be a claim about the time the imagery is from,
  // and Blue Marble is a composite with no single time.
  float l = 0.35 + 0.65 * max(dot(normalize(v_norm), normalize(u_sun)), 0.0);
  outColor = vec4(c * l, 1.0);
}")

(def ^:private surface-vs "#version 300 es
precision highp float;
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_colour;
uniform mat4 u_view_proj;
out vec3 v_colour;
void main() {
  v_colour = a_colour;
  vec4 p = u_view_proj * vec4(a_pos, 1.0);
  p.z = 2.0 * p.z - p.w;
  gl_Position = p;
}")

(def ^:private surface-fs "#version 300 es
precision highp float;
in vec3 v_colour;
out vec4 outColor;
void main() { outColor = vec4(v_colour, 1.0); }")

(def ^:private building-vs "#version 300 es
precision highp float;
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_norm;
uniform mat4 u_view_proj;
out vec3 v_norm;
void main() {
  v_norm = a_norm;
  vec4 p = u_view_proj * vec4(a_pos, 1.0);
  p.z = 2.0 * p.z - p.w;
  gl_Position = p;
}")

(def ^:private building-fs "#version 300 es
precision highp float;
in vec3 v_norm;
uniform vec3 u_sun;
out vec4 outColor;
void main() {
  // Roofs (normal along the surface) read brighter than walls (normal
  // horizontal). That contrast is the only thing that makes a block of
  // extrusions read as buildings rather than as one grey mass, and it is
  // why the walls carry an outward normal rather than the surface one.
  float l = 0.30 + 0.70 * max(dot(normalize(v_norm), normalize(u_sun)), 0.0);
  outColor = vec4(vec3(0.82, 0.84, 0.92) * l, 1.0);
}")

(def ^:private line-vs "#version 300 es
precision highp float;
layout(location=0) in vec3 a_pos;
uniform mat4 u_view_proj;
void main() {
  vec4 p = u_view_proj * vec4(a_pos, 1.0);
  p.z = 2.0 * p.z - p.w;
  gl_Position = p;
}")

(def ^:private line-fs "#version 300 es
precision highp float;
uniform vec4 u_colour;
out vec4 outColor;
void main() { outColor = u_colour; }")

(def ^:private marker-vs "#version 300 es
precision highp float;
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_colour;
layout(location=2) in float a_size;
uniform mat4 u_view_proj;
uniform float u_dpr;
out vec3 v_colour;
void main() {
  v_colour = a_colour;
  vec4 p = u_view_proj * vec4(a_pos, 1.0);
  p.z = 2.0 * p.z - p.w;
  gl_Position = p;
  // Shrink with distance, but never below a pixel: a marker that
  // disappears when the camera pulls back reads as missing data.
  gl_PointSize = max(2.0, a_size * u_dpr * (1.5 / max(p.w, 0.1)));
}")

(def ^:private marker-fs "#version 300 es
precision highp float;
in vec3 v_colour;
out vec4 outColor;
void main() {
  vec2 d = gl_PointCoord - vec2(0.5);
  float r = length(d);
  if (r > 0.5) discard;
  // A soft edge, so a 3-pixel dot is not a square.
  float a = smoothstep(0.5, 0.28, r);
  outColor = vec4(v_colour, a);
}")

(defn- compile-shader [gl type src]
  (let [s (.createShader gl type)]
    (.shaderSource gl s src)
    (.compileShader gl s)
    (when-not (.getShaderParameter gl s (.-COMPILE_STATUS gl))
      (throw (ex-info "shader did not compile"
                      {:log (.getShaderInfoLog gl s)
                       :src (subs src 0 200)})))
    s))

(defn- program [gl vs fs]
  (let [p (.createProgram gl)]
    (.attachShader gl p (compile-shader gl (.-VERTEX_SHADER gl) vs))
    (.attachShader gl p (compile-shader gl (.-FRAGMENT_SHADER gl) fs))
    (.linkProgram gl p)
    (when-not (.getProgramParameter gl p (.-LINK_STATUS gl))
      (throw (ex-info "program did not link" {:log (.getProgramInfoLog gl p)})))
    p))

(defn- buffer [gl target data]
  (let [b (.createBuffer gl)]
    (.bindBuffer gl target b)
    (.bufferData gl target data (.-STATIC_DRAW gl))
    b))

(defn create
  "Set up on a canvas. Returns the backend state, or nil if WebGL 2 is
  unavailable -- nil rather than a throw, because the caller's next move
  is to say so in the UI, not to crash."
  [canvas]
  (when-let [gl (.getContext canvas "webgl2" #js {:antialias true
                                                  :alpha false
                                                  :powerPreference "high-performance"})]
    {:backend :webgl2
     :gl gl
     :canvas canvas
     :tile-prog (program gl tile-vs tile-fs)
     :line-prog (program gl line-vs line-fs)
     :building-prog (program gl building-vs building-fs)
     :surface-prog (program gl surface-vs surface-fs)
     :marker-prog (program gl marker-vs marker-fs)
     :tiles (atom {})        ; {tile-key {:vao :count :tex}}
     :lines (atom nil)
     :markers (atom nil)
     :buildings (atom nil)
     :surface (atom nil)}))

(defn- tile-key [{:keys [z x y]}] (str z "/" x "/" y))

(defn ensure-tile!
  "Upload one tile's mesh and texture if it is not already resident.

  `image` is a decoded `ImageBitmap`. Textures are kept until `drop-tiles!`
  removes them: a tile that leaves the view usually comes back, and
  re-uploading it every frame is what makes a globe stutter when it spins."
  [{:keys [gl tiles]} coord image segments]
  (let [k (tile-key coord)]
    (when-not (get @tiles k)
      (let [{:keys [vertices indices]} (mesh/globe-tile-patch-terrain coord 1.0 segments 0.0)
            vao (.createVertexArray gl)]
        (.bindVertexArray gl vao)
        (buffer gl (.-ARRAY_BUFFER gl) (js/Float32Array. (clj->js vertices)))
        ;; pos3 + norm3 + uv2, 8 floats = 32 bytes.
        (doseq [[loc size off] [[0 3 0] [1 3 12] [2 2 24]]]
          (.enableVertexAttribArray gl loc)
          (.vertexAttribPointer gl loc size (.-FLOAT gl) false 32 off))
        (buffer gl (.-ELEMENT_ARRAY_BUFFER gl) (js/Uint16Array. (clj->js indices)))
        (let [tex (.createTexture gl)]
          (.bindTexture gl (.-TEXTURE_2D gl) tex)
          (.texImage2D gl (.-TEXTURE_2D gl) 0 (.-RGBA gl) (.-RGBA gl)
                       (.-UNSIGNED_BYTE gl) image)
          (.generateMipmap gl (.-TEXTURE_2D gl))
          ;; CLAMP_TO_EDGE on both axes: REPEAT wraps a tile's right edge
          ;; onto its left and draws a thin mirror of the far side of the
          ;; tile along every seam.
          (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_WRAP_S gl) (.-CLAMP_TO_EDGE gl))
          (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_WRAP_T gl) (.-CLAMP_TO_EDGE gl))
          (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_MIN_FILTER gl) (.-LINEAR_MIPMAP_LINEAR gl))
          (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_MAG_FILTER gl) (.-LINEAR gl))
          (.bindVertexArray gl nil)
          (swap! tiles assoc k {:vao vao :count (count indices) :tex tex}))))))

(defn drop-tiles!
  "Release every tile not in `keep-set`. Called when the zoom changes."
  [{:keys [gl tiles]} keep-set]
  (doseq [[k {:keys [vao tex]}] @tiles
          :when (not (contains? keep-set k))]
    (.deleteVertexArray gl vao)
    (.deleteTexture gl tex)
    (swap! tiles dissoc k)))

(defn set-lines! [{:keys [gl lines]} verts]
  (when-let [old @lines] (.deleteVertexArray gl (:vao old)))
  (let [vao (.createVertexArray gl)]
    (.bindVertexArray gl vao)
    (buffer gl (.-ARRAY_BUFFER gl) (js/Float32Array. (clj->js verts)))
    (.enableVertexAttribArray gl 0)
    (.vertexAttribPointer gl 0 3 (.-FLOAT gl) false 12 0)
    (.bindVertexArray gl nil)
    (reset! lines {:vao vao :count (/ (count verts) 3)})))

(defn set-surface!
  "Upload the ground polygons. `nil` clears them."
  [{:keys [gl surface]} mesh]
  (when-let [old @surface] (.deleteVertexArray gl (:vao old)))
  (if (or (nil? mesh) (empty? (:indices mesh)))
    (reset! surface nil)
    (let [vao (.createVertexArray gl)]
      (.bindVertexArray gl vao)
      (buffer gl (.-ARRAY_BUFFER gl) (js/Float32Array. (clj->js (:vertices mesh))))
      (doseq [[loc size off] [[0 3 0] [1 3 12]]]
        (.enableVertexAttribArray gl loc)
        (.vertexAttribPointer gl loc size (.-FLOAT gl) false 32 off))
      (buffer gl (.-ELEMENT_ARRAY_BUFFER gl) (js/Uint32Array. (clj->js (:indices mesh))))
      (.bindVertexArray gl nil)
      (reset! surface {:vao vao :count (count (:indices mesh))}))))

(defn set-buildings!
  "Upload the extruded building mesh. `nil` clears it -- which is what
  leaving a covered area must do, or the last city stays welded to the
  globe and travels with it."
  [{:keys [gl buildings]} mesh]
  (when-let [old @buildings]
    (.deleteVertexArray gl (:vao old)))
  (if (or (nil? mesh) (empty? (:indices mesh)))
    (reset! buildings nil)
    (let [vao (.createVertexArray gl)]
      (.bindVertexArray gl vao)
      (buffer gl (.-ARRAY_BUFFER gl) (js/Float32Array. (clj->js (:vertices mesh))))
      ;; pos3 + norm3 + uv2 = 8 floats; the uv is unused here but the
      ;; stride is the mesh library's, not this file's to choose.
      (doseq [[loc size off] [[0 3 0] [1 3 12]]]
        (.enableVertexAttribArray gl loc)
        (.vertexAttribPointer gl loc size (.-FLOAT gl) false 32 off))
      ;; Uint32: a city block passes 65,535 vertices quickly, and
      ;; Uint16 would silently wrap rather than fail.
      (buffer gl (.-ELEMENT_ARRAY_BUFFER gl) (js/Uint32Array. (clj->js (:indices mesh))))
      (.bindVertexArray gl nil)
      (reset! buildings {:vao vao :count (count (:indices mesh))}))))

(defn set-markers! [{:keys [gl markers]} verts]
  (when-let [old @markers] (.deleteVertexArray gl (:vao old)))
  (let [vao (.createVertexArray gl)]
    (.bindVertexArray gl vao)
    (buffer gl (.-ARRAY_BUFFER gl) (js/Float32Array. (clj->js verts)))
    ;; pos3 + colour3 + size1 = 7 floats = 28 bytes.
    (doseq [[loc size off] [[0 3 0] [1 3 12] [2 1 24]]]
      (.enableVertexAttribArray gl loc)
      (.vertexAttribPointer gl loc size (.-FLOAT gl) false 28 off))
    (.bindVertexArray gl nil)
    (reset! markers {:vao vao :count (/ (count verts) 7)})))

(defn draw!
  [{:keys [gl canvas tile-prog line-prog marker-prog building-prog surface-prog
           tiles lines markers buildings surface]}
   {:keys [view-proj dpr]}]
  (let [w (.-width canvas) h (.-height canvas)]
    (.viewport gl 0 0 w h)
    (.clearColor gl 0.02 0.03 0.06 1.0)
    (.clearDepth gl 1.0)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
    (.enable gl (.-DEPTH_TEST gl))
    (.depthFunc gl (.-LEQUAL gl))
    (.enable gl (.-CULL_FACE gl))
    (.disable gl (.-BLEND gl))

    ;; 1. tiles
    (.useProgram gl tile-prog)
    (.uniformMatrix4fv gl (.getUniformLocation gl tile-prog "u_view_proj")
                       false (js/Float32Array. (clj->js view-proj)))
    (.uniform3f gl (.getUniformLocation gl tile-prog "u_sun") 0.6 0.5 0.6)
    (.uniform1i gl (.getUniformLocation gl tile-prog "u_tex") 0)
    (.activeTexture gl (.-TEXTURE0 gl))
    (doseq [[_ {:keys [vao count tex]}] @tiles]
      (.bindTexture gl (.-TEXTURE_2D gl) tex)
      (.bindVertexArray gl vao)
      (.drawElements gl (.-TRIANGLES gl) count (.-UNSIGNED_SHORT gl) 0))

    ;; 1a. ground polygons -- water, landcover, parks. Drawn after the
    ;; raster tiles and before the buildings, with culling off because an
    ;; earcut ring's winding is whatever the source gave it.
    (when-let [{:keys [vao count]} (when (pos? (:count @surface 0)) @surface)]
      (.disable gl (.-CULL_FACE gl))
      (.useProgram gl surface-prog)
      (.uniformMatrix4fv gl (.getUniformLocation gl surface-prog "u_view_proj")
                         false (js/Float32Array. (clj->js view-proj)))
      (.bindVertexArray gl vao)
      (.drawElements gl (.-TRIANGLES gl) count (.-UNSIGNED_INT gl) 0)
      (.enable gl (.-CULL_FACE gl)))

    ;; 1b. buildings -- same depth state as the tiles, drawn before the
    ;; lines so a coastline crossing a city is still visible over it.
    (when-let [{:keys [vao count]} (when (pos? (:count @buildings 0)) @buildings)]
      (.useProgram gl building-prog)
      (.uniformMatrix4fv gl (.getUniformLocation gl building-prog "u_view_proj")
                         false (js/Float32Array. (clj->js view-proj)))
      (.uniform3f gl (.getUniformLocation gl building-prog "u_sun") 0.6 0.5 0.6)
      (.bindVertexArray gl vao)
      (.drawElements gl (.-TRIANGLES gl) count (.-UNSIGNED_INT gl) 0))

    ;; 2. lines
    (.disable gl (.-CULL_FACE gl))
    (.enable gl (.-BLEND gl))
    (.blendFunc gl (.-SRC_ALPHA gl) (.-ONE_MINUS_SRC_ALPHA gl))
    (when-let [{:keys [vao count]} (when (pos? (:count @lines 0)) @lines)]
      (.useProgram gl line-prog)
      (.uniformMatrix4fv gl (.getUniformLocation gl line-prog "u_view_proj")
                         false (js/Float32Array. (clj->js view-proj)))
      (.uniform4f gl (.getUniformLocation gl line-prog "u_colour") 0.55 0.75 0.9 0.55)
      (.bindVertexArray gl vao)
      (.drawArrays gl (.-LINES gl) 0 count))

    ;; 3. markers -- depth TEST but no depth WRITE
    (when-let [{:keys [vao count]} (when (pos? (:count @markers 0)) @markers)]
      (.depthMask gl false)
      (.useProgram gl marker-prog)
      (.uniformMatrix4fv gl (.getUniformLocation gl marker-prog "u_view_proj")
                         false (js/Float32Array. (clj->js view-proj)))
      (.uniform1f gl (.getUniformLocation gl marker-prog "u_dpr") (or dpr 1.0))
      (.bindVertexArray gl vao)
      (.drawArrays gl (.-POINTS gl) 0 count)
      (.depthMask gl true))

    (.bindVertexArray gl nil)
    ;; Report the GL error code and what was actually submitted. A WebGL
    ;; context does not throw: it records an error and keeps going, so a
    ;; misconfigured draw is a blank canvas and a silent number.
    (let [d (or (aget js/window "__tenkyu") #js {})]
      (aset d "glError" (.getError gl))
      (aset d "glTiles" (count @tiles))
      (aset d "glLines" (:count @lines 0))
      (aset d "glMarkers" (:count @markers 0))
      (aset d "glBuildings" (:count @buildings 0))
      (aset d "glSurface" (:count @surface 0))
      (aset js/window "__tenkyu" d))))
