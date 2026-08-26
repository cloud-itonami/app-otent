(ns app-otent.globe.webgpu
  "The WebGPU backend: preferred where the browser has it.

  Same scene, same vertex data, same three passes as
  `app-otent.globe.webgl` -- the geometry all comes from
  `app-otent.globe.scene`, which is why the two can be compared at all.

  ## What WebGPU actually buys here

  Not frame rate, at this scene size. It buys **explicit state**: the
  pipeline declares its depth format, blend mode and vertex layout once,
  at creation, instead of being a global machine that the last draw call
  left in some configuration. The WebGL path above has to remember to put
  `depthMask` back.

  ## Point sprites do not exist

  WebGL gives `gl_PointSize` and `gl_PointCoord`; WGSL has neither, and
  `point-list` topology is one pixel with no size and no coverage. So
  markers are **instanced quads**: six vertices generated in the shader
  from `vertex_index`, offset in clip space by the instance's size. That
  is a real difference between the backends and it is confined to this
  one shader -- the vertex DATA is byte-identical, which is what the
  parity test asserts."
  (:require [app-otent.globe.scene :as scene]
            [kotoba.geo.mesh :as mesh]))

(def ^:private tile-wgsl "
struct Uniforms { view_proj: mat4x4<f32>, sun: vec4<f32> };
@group(0) @binding(0) var<uniform> u: Uniforms;
@group(0) @binding(1) var samp: sampler;
@group(0) @binding(2) var tex: texture_2d<f32>;

struct VOut {
  @builtin(position) pos: vec4<f32>,
  @location(0) uv: vec2<f32>,
  @location(1) norm: vec3<f32>,
};

@vertex
fn vs(@location(0) p: vec3<f32>, @location(1) n: vec3<f32>, @location(2) uv: vec2<f32>) -> VOut {
  var o: VOut;
  o.pos = u.view_proj * vec4<f32>(p, 1.0);
  o.uv = uv;
  o.norm = n;
  return o;
}

@fragment
fn fs(i: VOut) -> @location(0) vec4<f32> {
  let c = textureSample(tex, samp, i.uv).rgb;
  let l = 0.35 + 0.65 * max(dot(normalize(i.norm), normalize(u.sun.xyz)), 0.0);
  return vec4<f32>(c * l, 1.0);
}")

(def ^:private surface-wgsl "
struct Uniforms { view_proj: mat4x4<f32>, unused: vec4<f32> };
@group(0) @binding(0) var<uniform> u: Uniforms;

struct VOut {
  @builtin(position) pos: vec4<f32>,
  @location(0) colour: vec3<f32>,
};

@vertex
fn vs(@location(0) p: vec3<f32>, @location(1) c: vec3<f32>) -> VOut {
  var o: VOut;
  o.pos = u.view_proj * vec4<f32>(p, 1.0);
  o.colour = c;
  return o;
}

@fragment
fn fs(i: VOut) -> @location(0) vec4<f32> { return vec4<f32>(i.colour, 1.0); }")

(def ^:private building-wgsl "
struct Uniforms { view_proj: mat4x4<f32>, sun: vec4<f32> };
@group(0) @binding(0) var<uniform> u: Uniforms;

struct VOut {
  @builtin(position) pos: vec4<f32>,
  @location(0) norm: vec3<f32>,
};

@vertex
fn vs(@location(0) p: vec3<f32>, @location(1) n: vec3<f32>) -> VOut {
  var o: VOut;
  o.pos = u.view_proj * vec4<f32>(p, 1.0);
  o.norm = n;
  return o;
}

@fragment
fn fs(i: VOut) -> @location(0) vec4<f32> {
  // Roofs read brighter than walls. That contrast is the only thing making
  // a block of extrusions read as buildings rather than one grey mass.
  let l = 0.30 + 0.70 * max(dot(normalize(i.norm), normalize(u.sun.xyz)), 0.0);
  return vec4<f32>(vec3<f32>(0.82, 0.84, 0.92) * l, 1.0);
}")

(def ^:private line-wgsl "
struct Uniforms { view_proj: mat4x4<f32>, colour: vec4<f32> };
@group(0) @binding(0) var<uniform> u: Uniforms;

@vertex
fn vs(@location(0) p: vec3<f32>) -> @builtin(position) vec4<f32> {
  return u.view_proj * vec4<f32>(p, 1.0);
}

@fragment
fn fs() -> @location(0) vec4<f32> { return u.colour; }")

(def ^:private marker-wgsl "
struct Uniforms { view_proj: mat4x4<f32>, viewport: vec4<f32> };
@group(0) @binding(0) var<uniform> u: Uniforms;

struct VOut {
  @builtin(position) pos: vec4<f32>,
  @location(0) colour: vec3<f32>,
  @location(1) local: vec2<f32>,
};

// WGSL has no point size and no point coord. Each marker is an instanced
// quad: six vertices built here from vertex_index, offset in CLIP space so
// the offset is in pixels regardless of how far away the marker is.
@vertex
fn vs(@builtin(vertex_index) vi: u32,
      @location(0) centre: vec3<f32>,
      @location(1) colour: vec3<f32>,
      @location(2) size: f32) -> VOut {
  var corners = array<vec2<f32>, 6>(
    vec2<f32>(-1.0, -1.0), vec2<f32>(1.0, -1.0), vec2<f32>(-1.0, 1.0),
    vec2<f32>(-1.0,  1.0), vec2<f32>(1.0, -1.0), vec2<f32>( 1.0, 1.0));
  let corner = corners[vi];
  var clip = u.view_proj * vec4<f32>(centre, 1.0);
  let px = max(2.0, size * 1.5 / max(clip.w, 0.1)) * u.viewport.z;
  // Multiplying by clip.w undoes the perspective divide the rasteriser is
  // about to do, so the offset survives as a constant number of pixels.
  clip.x = clip.x + corner.x * px * clip.w / u.viewport.x;
  clip.y = clip.y + corner.y * px * clip.w / u.viewport.y;
  var o: VOut;
  o.pos = clip;
  o.colour = colour;
  o.local = corner;
  return o;
}

@fragment
fn fs(i: VOut) -> @location(0) vec4<f32> {
  let r = length(i.local);
  if (r > 1.0) { discard; }
  let a = smoothstep(1.0, 0.56, r);
  return vec4<f32>(i.colour, a);
}")

(defn available?
  "Is there a WebGPU adapter at all? Async, because `requestAdapter` is."
  []
  (if-not (exists? js/navigator.gpu)
    (js/Promise.resolve false)
    (-> (.requestAdapter ^js js/navigator.gpu)
        (.then some?)
        (.catch (constantly false)))))

(defn- f32 [xs] (js/Float32Array. (clj->js xs)))

(defn- gpu-buffer [^js device data usage]
  ;; `^js` on every GPU handle, throughout this namespace.
  ;;
  ;; Closure has no externs for WebGPU, so under `:advanced` it renames
  ;; `.getMappedRange` to something short and the call becomes `undefined`
  ;; at runtime -- a bundle that compiles clean and throws on the first
  ;; frame. `:infer-externs` reports exactly this, and the hint is the
  ;; answer to it rather than a way to quieten it.
  (let [^js b (.createBuffer ^js device #js {:size (max 16 (.-byteLength data))
                                         :usage usage
                                         :mappedAtCreation true})]
    (.set (js/Float32Array. (.getMappedRange b)) data)
    (.unmap b)
    b))

(defn- u32-buffer [^js device data]
  (let [^js b (.createBuffer device #js {:size (max 16 (.-byteLength data))
                                         :usage (bit-or js/GPUBufferUsage.INDEX js/GPUBufferUsage.COPY_DST)
                                         :mappedAtCreation true})]
    (.set (js/Uint32Array. (.getMappedRange b)) data)
    (.unmap b)
    b))

(defn- index-buffer [^js device data]
  (let [^js b (.createBuffer ^js device #js {:size (max 16 (.-byteLength data))
                                         :usage (bit-or js/GPUBufferUsage.INDEX js/GPUBufferUsage.COPY_DST)
                                         :mappedAtCreation true})]
    (.set (js/Uint16Array. (.getMappedRange b)) data)
    (.unmap b)
    b))

(defn create
  "Acquire a device and build the three pipelines. Async; resolves to the
  backend state, or nil when WebGPU is not usable -- nil rather than a
  throw, because the caller's next move is the WebGL path."
  [^js canvas]
  (if-not (exists? js/navigator.gpu)
    (js/Promise.resolve nil)
    (-> (.requestAdapter ^js js/navigator.gpu #js {:powerPreference "high-performance"})
        (.then
         (fn [^js adapter]
           (if-not adapter
             nil
             (-> (.requestDevice ^js adapter)
                 (.then
                  (fn [^js device]
                    (let [^js ctx (.getContext canvas "webgpu")
                          fmt (.getPreferredCanvasFormat ^js js/navigator.gpu)]
                      (.configure ctx #js {:device device :format fmt :alphaMode "opaque"})
                      (let [mk (fn [wgsl layout blend? topology]
                                 (.createRenderPipeline
                                  ^js device
                                  #js {:layout "auto"
                                       :vertex #js {:module (.createShaderModule ^js device #js {:code wgsl})
                                                    :entryPoint "vs"
                                                    :buffers layout}
                                       :fragment #js {:module (.createShaderModule ^js device #js {:code wgsl})
                                                      :entryPoint "fs"
                                                      :targets (clj->js
                                                                [(cond-> {:format fmt}
                                                                   blend?
                                                                   (assoc :blend
                                                                          {:color {:srcFactor "src-alpha"
                                                                                   :dstFactor "one-minus-src-alpha"}
                                                                           :alpha {:srcFactor "one"
                                                                                   :dstFactor "one-minus-src-alpha"}}))])}
                                       :primitive #js {:topology topology
                                                       :cullMode (if (= topology "triangle-list")
                                                                   "back" "none")}
                                       :depthStencil #js {:format "depth24plus"
                                                          :depthWriteEnabled (not blend?)
                                                          :depthCompare "less-equal"}}))]
                        {:backend :webgpu
                         :device device :ctx ctx :canvas canvas :format fmt
                         :depth (atom nil)
                         :tile-pipeline
                         (mk tile-wgsl
                             (clj->js [{:arrayStride 32
                                        :attributes [{:shaderLocation 0 :offset 0 :format "float32x3"}
                                                     {:shaderLocation 1 :offset 12 :format "float32x3"}
                                                     {:shaderLocation 2 :offset 24 :format "float32x2"}]}])
                             false "triangle-list")
                         :surface-pipeline
                         (mk surface-wgsl
                             (clj->js [{:arrayStride 32
                                        :attributes [{:shaderLocation 0 :offset 0 :format "float32x3"}
                                                     {:shaderLocation 1 :offset 12 :format "float32x3"}]}])
                             false "triangle-list")
                         :building-pipeline
                         (mk building-wgsl
                             (clj->js [{:arrayStride 32
                                        :attributes [{:shaderLocation 0 :offset 0 :format "float32x3"}
                                                     {:shaderLocation 1 :offset 12 :format "float32x3"}]}])
                             false "triangle-list")
                         :line-pipeline
                         (mk line-wgsl
                             (clj->js [{:arrayStride 12
                                        :attributes [{:shaderLocation 0 :offset 0 :format "float32x3"}]}])
                             true "line-list")
                         :marker-pipeline
                         (mk marker-wgsl
                             (clj->js [{:arrayStride 28
                                        :stepMode "instance"
                                        :attributes [{:shaderLocation 0 :offset 0 :format "float32x3"}
                                                     {:shaderLocation 1 :offset 12 :format "float32x3"}
                                                     {:shaderLocation 2 :offset 24 :format "float32"}]}])
                             true "triangle-list")
                         :sampler (.createSampler ^js device #js {:magFilter "linear"
                                                              :minFilter "linear"
                                                              :mipmapFilter "linear"
                                                              :addressModeU "clamp-to-edge"
                                                              :addressModeV "clamp-to-edge"})
                         ;; THREE uniform buffers, not one.
                         ;;
                         ;; `queue.writeBuffer` runs when it is called;
                         ;; render-pass commands run when the encoder is
                         ;; submitted. Writing one buffer three times between
                         ;; three `draw` calls therefore does NOT give three
                         ;; passes three values -- all three read whatever was
                         ;; written last, and the result is a globe lit by the
                         ;; marker viewport. One buffer per pass is the fix;
                         ;; dynamic offsets into one buffer would also work and
                         ;; are harder to read.
                         :uniforms
                         (into {} (for [k [:tile :line :marker :building :surface]]
                                    [k (.createBuffer ^js device
                                                      #js {:size 96
                                                           :usage (bit-or js/GPUBufferUsage.UNIFORM
                                                                          js/GPUBufferUsage.COPY_DST)})]))
                         :tiles (atom {})
                         :lines (atom nil)
                         :markers (atom nil)
                         :buildings (atom nil)
                         :surface (atom nil)}))))))))
        (.catch (fn [_] nil)))))

(defn- tile-key [{:keys [z x y]}] (str z "/" x "/" y))

(defn ensure-tile! [{:keys [^js device tiles ^js sampler ^js tile-pipeline uniforms]} coord ^js image segments]
  (let [k (tile-key coord)]
    (when-not (get @tiles k)
      (let [{:keys [vertices indices]} (mesh/globe-tile-patch-terrain coord 1.0 segments 0.0)
            ^js tex (.createTexture ^js device
                                #js {:size #js {:width (.-width image) :height (.-height image)}
                                     :format "rgba8unorm"
                                     :usage (bit-or js/GPUTextureUsage.TEXTURE_BINDING
                                                    js/GPUTextureUsage.COPY_DST
                                                    js/GPUTextureUsage.RENDER_ATTACHMENT)})]
        (.copyExternalImageToTexture ^js (.-queue ^js device)
                                     #js {:source image}
                                     #js {:texture tex}
                                     #js {:width (.-width image) :height (.-height image)})
        (swap! tiles assoc k
               {:vbuf (gpu-buffer device (f32 vertices)
                                  (bit-or js/GPUBufferUsage.VERTEX js/GPUBufferUsage.COPY_DST))
                :ibuf (index-buffer device (js/Uint16Array. (clj->js indices)))
                :count (count indices)
                :tex tex
                :bind (.createBindGroup ^js device
                                        #js {:layout (.getBindGroupLayout ^js tile-pipeline 0)
                                             :entries (clj->js
                                                       [{:binding 0 :resource {:buffer (:tile uniforms) :size 96}}
                                                        {:binding 1 :resource sampler}
                                                        {:binding 2 :resource (.createView ^js tex)}])})})))))

(defn drop-tiles! [{:keys [tiles]} keep-set]
  (doseq [[k {:keys [^js vbuf ^js ibuf ^js tex]}] @tiles
          :when (not (contains? keep-set k))]
    (.destroy vbuf) (.destroy ibuf) (.destroy tex)
    (swap! tiles dissoc k)))

(defn set-lines! [{:keys [^js device lines ^js line-pipeline uniforms]} verts]
  (when-let [old @lines] (.destroy ^js (:vbuf old)))
  (reset! lines
          {:vbuf (gpu-buffer device (f32 verts)
                             (bit-or js/GPUBufferUsage.VERTEX js/GPUBufferUsage.COPY_DST))
           :count (/ (count verts) 3)
           :bind (.createBindGroup ^js device
                                   #js {:layout (.getBindGroupLayout ^js line-pipeline 0)
                                        :entries (clj->js [{:binding 0
                                                            :resource {:buffer (:line uniforms) :size 96}}])})}))

(defn set-surface!
  "Upload the ground polygons. `nil` clears them."
  [{:keys [^js device surface ^js surface-pipeline uniforms]} mesh]
  (when-let [old @surface]
    (.destroy ^js (:vbuf old)) (.destroy ^js (:ibuf old)))
  (if (or (nil? mesh) (empty? (:indices mesh)))
    (reset! surface nil)
    (reset! surface
            {:vbuf (gpu-buffer device (f32 (:vertices mesh))
                               (bit-or js/GPUBufferUsage.VERTEX js/GPUBufferUsage.COPY_DST))
             :ibuf (u32-buffer device (js/Uint32Array. (clj->js (:indices mesh))))
             :count (count (:indices mesh))
             :bind (.createBindGroup ^js device
                                     #js {:layout (.getBindGroupLayout ^js surface-pipeline 0)
                                          :entries (clj->js [{:binding 0
                                                              :resource {:buffer (:surface uniforms)
                                                                         :size 96}}])})})))

(defn set-buildings!
  "Upload the extruded building mesh. `nil` clears it -- which is what
  leaving a covered area must do, or the last city stays welded to the
  globe and travels with it."
  [{:keys [^js device buildings ^js building-pipeline uniforms]} mesh]
  (when-let [old @buildings]
    (.destroy ^js (:vbuf old))
    (.destroy ^js (:ibuf old)))
  (if (or (nil? mesh) (empty? (:indices mesh)))
    (reset! buildings nil)
    (reset! buildings
            {:vbuf (gpu-buffer device (f32 (:vertices mesh))
                               (bit-or js/GPUBufferUsage.VERTEX js/GPUBufferUsage.COPY_DST))
             ;; uint32: a city block passes 65,535 vertices quickly, and
             ;; uint16 would silently wrap rather than fail.
             :ibuf (u32-buffer device (js/Uint32Array. (clj->js (:indices mesh))))
             :count (count (:indices mesh))
             :bind (.createBindGroup ^js device
                                     #js {:layout (.getBindGroupLayout ^js building-pipeline 0)
                                          :entries (clj->js [{:binding 0
                                                              :resource {:buffer (:building uniforms)
                                                                         :size 96}}])})})))

(defn set-markers! [{:keys [^js device markers ^js marker-pipeline uniforms]} verts]
  (when-let [old @markers] (.destroy ^js (:vbuf old)))
  (reset! markers
          {:vbuf (gpu-buffer device (f32 verts)
                             (bit-or js/GPUBufferUsage.VERTEX js/GPUBufferUsage.COPY_DST))
           :count (/ (count verts) 7)
           :bind (.createBindGroup ^js device
                                   #js {:layout (.getBindGroupLayout ^js marker-pipeline 0)
                                        :entries (clj->js [{:binding 0
                                                            :resource {:buffer (:marker uniforms) :size 96}}])})}))

(defn- ensure-depth! [{:keys [^js device ^js canvas depth]}]
  (let [w (.-width canvas) h (.-height canvas)
        cur @depth]
    (when (or (nil? cur) (not= [w h] (:size cur)))
      (some-> ^js (:tex cur) (.destroy))
      (reset! depth
              {:size [w h]
               :tex (.createTexture ^js device
                                    #js {:size #js {:width w :height h}
                                         :format "depth24plus"
                                         :usage js/GPUTextureUsage.RENDER_ATTACHMENT})}))
    (:tex @depth)))

(defn draw!
  [{:keys [^js device ^js ctx ^js canvas uniforms ^js tile-pipeline ^js line-pipeline
           ^js marker-pipeline ^js building-pipeline ^js surface-pipeline
           tiles lines markers buildings surface] :as state}
   {:keys [view-proj dpr]}]
  (let [^js q (.-queue device)
        ^js depth-tex (ensure-depth! state)
        ;; A 96-byte block per pass: mat4 (64) + two vec4 (32). The second
        ;; vec4 means `sun` to the tile shader, `colour` to the line shader
        ;; and `viewport` to the marker shader -- the same sixteen bytes read
        ;; three ways, in three separate buffers so the three readings do not
        ;; collapse into the last write.
        write! (fn [which tail]
                 (.writeBuffer ^js q ^js (get uniforms which) 0
                               (f32 (concat view-proj tail (repeat 4 0.0)))))
        _ (do (write! :tile [0.6 0.5 0.6 0.0])
              (write! :building [0.6 0.5 0.6 0.0])
              (write! :surface [0.0 0.0 0.0 0.0])
              (write! :line [0.55 0.75 0.9 0.55])
              (write! :marker [(.-width canvas) (.-height canvas) (or dpr 1.0) 0.0]))
        ^js enc (.createCommandEncoder device)
        ^js pass (.beginRenderPass
              enc
              #js {:colorAttachments
                   (clj->js [{:view (.createView ^js (.getCurrentTexture ^js ctx))
                              :clearValue {:r 0.02 :g 0.03 :b 0.06 :a 1.0}
                              :loadOp "clear" :storeOp "store"}])
                   :depthStencilAttachment
                   #js {:view (.createView ^js depth-tex)
                        :depthClearValue 1.0
                        :depthLoadOp "clear" :depthStoreOp "store"}})]
    ;; 1. tiles
    (.setPipeline pass tile-pipeline)
    (doseq [[_ {:keys [^js vbuf ^js ibuf count ^js bind]}] @tiles]
      (.setBindGroup pass 0 bind)
      (.setVertexBuffer pass 0 vbuf)
      (.setIndexBuffer pass ibuf "uint16")
      (.drawIndexed pass count))

    ;; 1a. ground polygons, between the raster and the buildings.
    (when-let [{:keys [^js vbuf ^js ibuf count ^js bind]}
               (when (pos? (:count @surface 0)) @surface)]
      (.setPipeline pass surface-pipeline)
      (.setBindGroup pass 0 bind)
      (.setVertexBuffer pass 0 vbuf)
      (.setIndexBuffer pass ibuf "uint32")
      (.drawIndexed pass count))

    ;; 1b. buildings, before the lines so a coastline crossing a city is
    ;; still visible over it.
    (when-let [{:keys [^js vbuf ^js ibuf count ^js bind]}
               (when (pos? (:count @buildings 0)) @buildings)]
      (.setPipeline pass building-pipeline)
      (.setBindGroup pass 0 bind)
      (.setVertexBuffer pass 0 vbuf)
      (.setIndexBuffer pass ibuf "uint32")
      (.drawIndexed pass count))

    ;; 2. lines -- same uniform slot, different meaning
    (when-let [{:keys [^js vbuf count ^js bind]} (when (pos? (:count @lines 0)) @lines)]
      (.setPipeline pass line-pipeline)
      (.setBindGroup pass 0 bind)
      (.setVertexBuffer pass 0 vbuf)
      (.draw pass count))

    ;; 3. markers -- six vertices per instance
    ;; An empty set is skipped, not drawn: WebGPU warns on every
    ;; instance-count-0 draw, and a console full of warnings is where a real
    ;; one goes to hide.
    (when-let [{:keys [^js vbuf count ^js bind]} (when (pos? (:count @markers 0)) @markers)]
      (.setPipeline pass marker-pipeline)
      (.setBindGroup pass 0 bind)
      (.setVertexBuffer pass 0 vbuf)
      (.draw pass 6 count))

    (.end pass)
    (.submit ^js q #js [(.finish ^js enc)])
    ;; Reported so the browser test's residency check is real on THIS
    ;; backend too. It was WebGL-only, so the check passed vacuously on
    ;; the preferred renderer -- "n/a (webgpu)" is what a check that
    ;; cannot fail looks like.
    (let [d (or (aget js/window "__otent") #js {})]
      (aset d "gpuTiles" (count @tiles))
      (aset d "gpuBuildings" (:count @buildings 0))
      (aset d "gpuSurface" (:count @surface 0))
      (aset js/window "__otent" d))))
