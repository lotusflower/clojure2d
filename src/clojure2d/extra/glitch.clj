;; # Namespace scope
;;
;; Various glitching pixel filters or functions
;;
;; * Slitscan
;; * Mirror
;; * Slitscan2
;; * Fold
;; * Blend two Pixels (compose)
;; * Reduce colors
;;
;; All filters are equiped with random configuration generator. This way you can easily search vast space of options.
;;
;; More info soon. Some API is subject to change (make it more consistent).

(ns clojure2d.extra.glitch
  (:require [clojure2d.math :as m]
            [clojure2d.math.random :as r]
            [clojure2d.pixels :as p]
            [clojure2d.core :refer :all]
            [clojure2d.math.vector :as v]
            [clojure2d.extra.signal :as s]
            [clojure2d.color :as c])
  (:import [clojure2d.pixels Pixels]
           [clojure2d.math.vector Vec2 Vec4]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)
(m/use-primitive-operators)

;; ## Slitscan

;; ### Simple slitscan
;;
;; Pixels are shifted by value returned by wave function. You have to provide separate wave functions for x and y axises.
;; Random setup is based on sum of oscillators defined in `signal` namespace.

(def freqs (mapv #(<< 1 ^long %) (range 8)))
(def amps (mapv #(/ ^long %) freqs))

(defn slitscan-random-setup
  "Create list of random waves "
  ([n]
   (letfn [(f []
             (let [r (r/randval 0.75 (r/irand 4) (r/irand (count freqs)))]
               {:wave (rand-nth s/oscillators)
                :freq (freqs r)
                :amp (amps r)
                :phase (r/drand)}))]
     (repeatedly n f)))
  ([]
   (slitscan-random-setup (r/irand 2 6))))

(defn make-slitscan-waves
  ""
  ([waves]
   (s/make-sum-wave (map #(s/make-wave (:wave %) (:freq %) (:amp %) (:phase %)) waves)))
  ([]
   (make-slitscan-waves (slitscan-random-setup))))

(defn slitscan
  ""
  [fx fy ch ^Pixels p x y]
  (let [wp (.w p)
        hp (.h p)
        sx (/ 1.0 wp)
        sy (/ 1.0 hp)
        shiftx (* 0.3 wp ^double (fx (* ^int x sx)))
        shifty (* 0.3 hp ^double (fy (* ^int y sy)))
        xx (m/wrap 0.0 wp (+ ^int x shiftx))
        yy (m/wrap 0.0 hp (+ ^int y shifty))]
    (p/get-value p ch xx yy)))

(defn make-slitscan-filter
  ""
  ([]
   (partial p/filter-channel-xy (partial slitscan (make-slitscan-waves) (make-slitscan-waves))))
  ([fx fy]
   (partial p/filter-channel-xy (partial slitscan fx fy))))

;; channel shifts

(defn make-shift-channels-filter
  ""
  [amount h v]
  (let [mv (constantly amount)
        zr (constantly 0.0)]
    (make-slitscan-filter (if h mv zr) (if v mv zr))))

(defn make-slitscan2-filter
  "f: Vec2 -> Vec2 (use variation)
   r: value 1.0-3.0"
  ([f ^double r]
   (let [r- (- r)]
     (fn [ch t ^Pixels p]
       (dotimes [y (.h p)]
         (let [^double yv (m/norm y 0.0 (.h p) r- r)]
           (dotimes [x (.w p)]
             (let [xlerp (m/norm x 0.0 (.w p))
                   v1 (f (Vec2. r- yv))
                   v2 (f (Vec2. r yv))
                   ^Vec2 vv (v/interpolate v1 v2 xlerp)
                   xx (unchecked-int (m/norm (.x vv) r- r 0.0 (.w p)))
                   yy (unchecked-int (m/norm (.y vv) r- r 0.0 (.h p)))]
               (p/set-value t ch x y (p/get-value p ch xx yy)))))))))
  ([f]
   (make-slitscan2-filter f 2.0)))

;;
(defn make-fold-filter
  "f: Vec2 -> Vec2 (use variation)
   r: value 1.0-3.0"
  ([f ^double r]
   (let [r- (- r)]
     (fn [ch t ^Pixels p]
       (dotimes [y (.h p)]
         (let [^double yv (m/norm y 0.0 (.h p) r- r)]
           (dotimes [x (.w p)]
             (let [^double xv (m/norm x 0.0 (.w p) r- r)
                   ^Vec2 vv (f (Vec2. xv yv))
                   xx (unchecked-int (m/norm (.x vv) r- r 0.0 (.w p)))
                   yy (unchecked-int (m/norm (.y vv) r- r 0.0 (.h p)))]
               (p/set-value t ch x y (p/get-value p ch xx yy)))))))))
  ([f]
   (make-fold-filter f 2.0)))

;; mirrorimage

(defn- mi-draw-point
  ""
  ([ch target source oldx oldy newx newy sx sy]
   (p/set-value target ch (+ ^long newx ^long sx) (+ ^long newy ^long sy)
                (p/get-value source ch (+ ^long oldx ^long sx) (+ ^long oldy ^long sy))))
  ([ch target source oldx oldy newx newy]
   (p/set-value target ch newx newy (p/get-value source ch oldx oldy))))

(defn- mi-do-horizontal
  ""
  [t ch target ^Pixels source]
  (dotimes [y (/ (.h source) 2)]
    (dotimes [x (.w source)]
      (if t
        (mi-draw-point ch target source x y x (- (.h source) y 1))
        (mi-draw-point ch target source x (- (.h source) y 1) x y)))))

(defn- mi-do-vertical
  ""
  [t ch target ^Pixels source]
  (dotimes [x (/ (.w source) 2)]
    (dotimes [y (.h source)]
      (if t
        (mi-draw-point ch target source x y (- (.w source) x 1) y)
        (mi-draw-point ch target source (- (.w source) x 1) y x y)))))

(defn- mi-do-diag-ul
  ""
  [t shift? ch target ^Pixels source]
  (let [^int t t
        size (min (.w source) (.h source))
        tx (if shift? (- (.w source) size) 0)
        ty (if shift? (- (.h source) size) 0)]
    (dotimes [y size]
      (dotimes [x (inc y)]
        (condp m/eq t
          0 (mi-draw-point ch target source x y y x tx ty)
          1 (mi-draw-point ch target source y x x y tx ty)
          2 (mi-draw-point ch target source x y (- size x 1) (- size y 1) tx ty)
          3 (mi-draw-point ch target source y x (- size y 1) (- size x 1) tx ty))))))

(defn- mi-do-diag-ur
  ""
  [t shift? ch target ^Pixels source]
  (let [^int t t
        size (min (.w source) (.h source))
        tx (if shift? (- (.w source) size) 0)
        ty (if shift? (- (.h source) size) 0)]
    (dotimes [y size]
      (loop [x (int (dec size))]
        (when (>= x (- size y 1))
          (condp m/eq t
            0 (mi-draw-point ch target source x y (- size y 1) (- size x 1) tx ty)
            1 (mi-draw-point ch target source (- size y 1) (- size x 1) x y tx ty)
            2 (mi-draw-point ch target source x y (- size x 1) (- size y 1) tx ty)
            3 (mi-draw-point ch target source (- size x 1) (- size y 1) x y tx ty))
          (recur (dec x)))))))

(defn- mi-do-diag-rect
  ""
  [t l ch target ^Pixels source]
  (dotimes [y (.h source)]
    (let [d (int (if t
                   (m/norm y 0 (.h source) 0 (.w source))
                   (m/norm y 0 (.h source) (.w source) 0)))]
      (dotimes [x d]
        (if l
          (mi-draw-point ch target source (- (.w source) x 1) (- (.h source) y 1) x y)
          (mi-draw-point ch target source x y (- (.w source) x 1) (- (.h source) y 1)))))))

(def mirror-types {:U    (partial mi-do-horizontal true)
                   :D    (partial mi-do-horizontal false)
                   :L    (partial mi-do-vertical true)
                   :R    (partial mi-do-vertical false)
                   :DL   (partial mi-do-diag-ul 0 false)
                   :UR   (partial mi-do-diag-ul 1 false)
                   :DL2  (partial mi-do-diag-ul 2 false)
                   :UR2  (partial mi-do-diag-ul 3 false)
                   :SDL  (partial mi-do-diag-ul 0 true)
                   :SUR  (partial mi-do-diag-ul 1 true)
                   :SDL2 (partial mi-do-diag-ul 2 true)
                   :SUR2 (partial mi-do-diag-ul 3 true)
                   :DR   (partial mi-do-diag-ur 0 false)
                   :UL   (partial mi-do-diag-ur 1 false)
                   :DR2  (partial mi-do-diag-ur 2 false)
                   :UL2  (partial mi-do-diag-ur 3 false)
                   :SDR  (partial mi-do-diag-ur 0 true)
                   :SUL  (partial mi-do-diag-ur 1 true)
                   :SDR2 (partial mi-do-diag-ur 2 true)
                   :SUL2 (partial mi-do-diag-ur 3 true)
                   :RUR  (partial mi-do-diag-rect true true)
                   :RDR  (partial mi-do-diag-rect false true)
                   :RDL  (partial mi-do-diag-rect true false)
                   :RUL  (partial mi-do-diag-rect false false)})

(defn make-mirror-filter
  ""
  [t]
  (t mirror-types))

;;

;; blend machine

(defn random-blend-get-cs
  "Return colorspace or nil"
  []
  (when (r/brand 0.9) (rand-nth c/colorspaces-names)))

(defn blend-machine
  "Do random blend of two pixels, use random colorspace"
  ([]
   (let [cs1 (random-blend-get-cs) ; let's convert to some colorspace (or leave rgb)
         cs2 (r/randval 0.2 (random-blend-get-cs) cs1) ; maybe different cs on second image?
         outcs (r/randval 0.2 (random-blend-get-cs) cs1) ; maybe some random colorspace on output?
         bl1 (r/randval 0.85 (rand-nth c/blends-names) nil)    ; ch1 blend
         bl2 (r/randval 0.85 (rand-nth c/blends-names) nil) ; ch2 blend
         bl3 (r/randval 0.85 (rand-nth c/blends-names) nil)] ; ch3 blend
     {:switch (r/brand 0.5)
      :in-cs1 cs1
      :in-cs2 cs2
      :out-cs outcs
      :blend-ch1 bl1
      :blend-ch2 bl2
      :blend-ch3 bl3}))
  ([p1 p2]
   (blend-machine (blend-machine) p1 p2))
  ([{:keys [switch in-cs1 in-cs2 out-cs blend-ch1 blend-ch2 blend-ch3]} p1 p2]
   (let [[p1 p2] (if switch [p2 p1] [p1 p2]) ; switch images
         result (p/compose-channels blend-ch1 blend-ch2 blend-ch3 nil
                                    (if in-cs1 (p/filter-colors (first (in-cs1 c/colorspaces)) p1) p1)
                                    (if in-cs2 (p/filter-colors (first (in-cs2 c/colorspaces)) p2) p2))]
     (if out-cs
       (p/filter-colors (second (out-cs c/colorspaces)) result)
       result))))

;; color reducer machine

(defn color-reducer-machine
  "Randomize color reducing filter, random method, random colors"
  ([]
   (let [bpal (condp #(> ^double %1 ^double %2) (r/drand 1.0)
                0.1 (let [num (r/irand 5 20)]
                      {:type :iq
                       :palette (c/make-iq-random-palette num)})
                0.5 {:type :colourlovers
                     :palette (rand-nth c/colourlovers-palettes)}
                0.6 (let [preset (rand-nth (keys c/paletton-presets))
                          p (rand-nth c/colourlovers-palettes)
                          h (map #(c/paletton-rgb-to-hue %) p)]
                      {:type :colourlovers-paletton
                       :conf {:hue h
                              :preset preset
                              :type :monochromatic
                              :compl false}
                       :palette (let [v (reduce #(concat %1 (c/make-monochromatic-palette %2 (preset c/paletton-presets))) p h)]
                                  (vec v))})
                (let [h (r/drand 360.0)
                      t (rand-nth [:monochromatic :triad :triad :triad :triad :triad :tetrad :tetrad :tetrad])
                      conf {:compl (r/brand 0.6)
                            :angle (r/drand 10.0 90.0)
                            :adj (r/brand 0.5)
                            :hue h
                            :preset (rand-nth (keys c/paletton-presets))
                            :type t}]
                  {:type :paletton
                   :conf conf
                   :palette (c/paletton-palette t h conf)}))
         pal (r/randval 0.2
                        (update bpal :palette conj (Vec4. 0.0 0.0 0.0 255.0) (Vec4. 255.0 255.0 255.0 255.0))
                        bpal)
         pal (assoc pal :distf (rand-nth [v/dist v/dist-abs v/dist-cheb v/dist-sq]))]
     pal))
  ([conf p]
   (p/filter-colors (c/make-reduce-color-filter (:distf conf) (:palette conf)) p))
  ([p]
   (color-reducer-machine p (color-reducer-machine))))

;; find best matching pixels

(defn blend-images-filter
  ""
  [{:keys [names pixels mode distance cs]
    :or {names [] pixels [] distance :euclid-sq mode :color cs :RGB}} ^Pixels p]
  (let [images (concat pixels (map (comp (partial p/filter-colors (first (c/colorspaces cs))) p/get-image-pixels load-image) names))
        ^int w (width p)
        ^int h (height p)
        df (v/distances distance)]
    (if (= mode :color)
      (p/filter-colors-xy (fn [^Vec4 c ^long x ^long y]
                            (first (reduce (fn [curr img]
                                             (let [nx (unchecked-int (m/norm x 0 w 0 (width img)))
                                                   ny (unchecked-int (m/norm y 0 h 0 (height img)))
                                                   [currc ^double currd] curr
                                                   nc (p/get-color img nx ny)
                                                   ^double nd (df c nc)] 
                                               (if (< nd currd) [nc nd] curr)))
                                           [c Double/MAX_VALUE] images))) p)
      (p/filter-channels (partial p/filter-channel-xy (fn [ch p ^long x ^long y]
                                                        (let [^int c (p/get-value p ch x y)]
                                                          (first (reduce (fn [curr img]
                                                                           (let [nx (unchecked-int (m/norm x 0 w 0 (width img)))
                                                                                 ny (unchecked-int (m/norm y 0 h 0 (height img)))
                                                                                 [currc ^double currd] curr
                                                                                 ^int nc (p/get-value img ch nx ny) 
                                                                                 nd (m/abs (- c nc))]
                                                                             (if (< nd currd) [nc nd] curr)))
                                                                         [c Double/MAX_VALUE] images))))) p))))
