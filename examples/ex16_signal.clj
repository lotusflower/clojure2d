;; apply analog filter on image

(ns examples.ex16-analog
  (:require [clojure2d.core :refer :all]
            [clojure2d.pixels :as p]
            [clojure2d.color :as c]
            [clojure2d.extra.signal :refer :all]))

;; load image
(def p (load-pixels "results/test.jpg"))

;; dj-eq
(def effect1 (make-effect :dj-eq {:lo -10 :mid 10 :hi -10 :peak_bw 1.3 :shelf_slope 1.5 :rate 44100}))

;; lowpass
(def effect2 (make-effect :simple-lowpass {:rate 44100 :cutoff 1000}))

;; filter with dj-eq
(save-pixels (p/filter-channels p/normalize (p/filter-channels (make-effects-filter [effect1] {:coding :alaw-rev} {:coding :ulaw}) p)) "results/ex16/djeq.jpg")

;; filter with 3 lowpass
(save-pixels (p/filter-channels (make-effects-filter [effect2 effect2 effect2] {:signed true}) p) "results/ex16/lowpass3.jpg")

;; filter with all in YPbPr colorspace
(let [filter (make-effects-filter [effect1 effect2])
      res (->> p
               (p/filter-colors c/to-YPbPr)
               (p/filter-channels filter)
               (p/filter-channels p/normalize)
               (p/filter-channels p/equalize-filter)
               (p/filter-colors c/from-YPbPr))]
  (save-pixels res "results/ex16/combined.jpg"))

(let [filter (make-effects-filter [(make-effect :divider {:denominator 7})])
      res (->> p
               (p/filter-colors c/to-OHTA)
               (p/filter-channels p/normalize)
               (p/filter-channels p/equalize-filter)
               (p/filter-channels filter filter filter nil)
               (p/filter-channels p/normalize)
               (p/filter-channels p/equalize-filter)
               (p/filter-colors c/from-YPbPr))]
  (save-pixels res "results/ex16/divider.jpg"))

;; full process without use of filter-channels
(let [effect [(make-effect :biquad-eq {:fc 2000 :gain -30 :bw 1 :fs 100000})]
      in (signal-from-pixels p {:layout :interleaved
                                        :coding :alaw
                                        :signed true
                                        :channels [2 0 1]
                                        :bits 16})
      res (apply-effects effect in)
      resp (signal-to-pixels (p/clone-pixels p) res {:layout :interleaved
                                                             :coding :alaw-rev
                                                             :signed true
                                                             :channels [2 0 1]
                                                             :bits 16})]
  (save-pixels (p/filter-channels p/normalize resp) "results/ex16/biquadeq.jpg"))


;; saving and loading signal to and from file
(save-signal (signal-from-pixels p {:layout :interleaved
                                    :coding :alaw
                                    :signed true
                                    :channels [2 0 1]
                                    :bits 16}) "results/ex16/signal.raw")

(load-signal "results/ex16/signal.raw")