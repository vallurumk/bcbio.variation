(ns bcbio.variation.multisample
  "Compare multiple sample input files, allowing flexible configuration
  of concordance/discordance logic for comparison two sets of calls."
  (:use [clojure.java.io]
        [clojure.set :only [intersection]]
        [ordered.map :only [ordered-map]])
  (:require [clojure.string :as string]
            [bcbio.run.fsp :as fsp]
            [bcbio.run.itx :as itx]
            [bcbio.variation.variantcontext :as gvc]))

(defn multiple-samples?
  "Check if the input VCF file has multiple genotyped samples."
  [in-file & {:keys [sample]}]
  (let [samples (-> in-file gvc/get-vcf-header .getGenotypeSamples)]
    (or (> (count samples) 1)
        (and (not (nil? sample))
             (not (contains? (set samples) sample))))))

(defn get-out-basename
  "Retrieve basename for output display, handling multiple sample inputs."
  [exp call in-files]
  (let [sample-name (or (:sample exp)
                        (-> in-files first gvc/get-vcf-header .getGenotypeSamples first
                            (str "multi")))]
    (format "%s-%s" sample-name (:name call))))

(defn- get-cmp-outfiles
  "Retrieve output files for a variant to variant comparison"
  [c1 c2 exp config]
  (let [out-dir (get-in config [:dir :out])
        base-out (str (file out-dir (format "%s-%s.vcf"
                                            (get-out-basename exp c1 [(:file c1)])
                                            (:name c2))))
        out-files (into (ordered-map :concordant (fsp/add-file-part base-out "concordant"))
                        (map (fn [c]
                               [(keyword (str (:name c) "-discordant"))
                                (fsp/add-file-part base-out (str (:name c) "-discordant"))])
                             [c1 c2]))]
    out-files))

(defn compare-genotypes
  "Compare two genotyping calls for a single sample, returning details about match:
   - concordant: 100% match between alleles
   - phasing-mismatch: Alleles match but phasing information does not.
   - nocall-mismatch: Alleles mismatch due to a no-call in one of the genotypes.
   - partial-mismatch: Alleles match in at least one position but mismatch elsewhere. 
   - discordant: No recoverable match characteristics"
  [g1 g2]
  (letfn [(has-nocall? [g]
            (some #(.isNoCall %) (:alleles g)))
          (phase-mismatch? [g1 g2]
            (and (or (:phased? g1) (:phased? g2))
                 (= (:alleles g1) (reverse (:alleles g2)))))
          (atleast-one-match? [g1 g2]
            (seq (intersection (set (:alleles g1)) (set (:alleles g2)))))
          (nocall-mismatch? [g1 g2]
            (and (or (has-nocall? g1) (has-nocall? g2))
                 (atleast-one-match? g1 g2)))
          (num-matches [g1 g2]
            (count (intersection
                    (set (map #(.getDisplayString %) (:alleles g1)))
                    (set (map #(.getDisplayString %) (:alleles g2))))))]
    (cond
     (= (:alleles g1) (:alleles g2)) :concordant
     (phase-mismatch? g1 g2) :phasing-mismatch
     (nocall-mismatch? g1 g2) :phasing-nocall
     (atleast-one-match? g1 g2) :partial-mismatch
     :else :discordant)))

(defn- same-vc-coords?
  "Check if variants have the same position and reference allele."
  [& xs]
  (apply = (map (juxt :chr :start :end :ref-allele) xs)))

(defmulti compare-vcs
  "Flexible comparison of variants, assuming pre-checking of
   vc1 and vc2 to overlap in the same genomic region."
  (fn [vc1 vc2 params]
    (cond
     (not (nil? (:compare-approach params))) (keyword (:compare-approach params))
     (or (> (:num-samples vc1) 1)
         (> (:num-samples vc2) 1)) :multiple
     :else :default)))

(defmethod compare-vcs :multiple
  ^{:doc "Compare variant contexts handling multiple sample comparisons."}
  [vc1 vc2 params]
  (letfn [(calc-genotype-score [g1 g2]
            (case (compare-genotypes g1 g2)
              :concordant 1.0
              :phasing-mismatch 1.0
              :nocall-mismatch 0.5
              :partial-mismatch 0.5
              0.0))]
    (when (same-vc-coords? vc1 vc2)
      (let [score-thresh (get params :multiple-thresh 1.0)
            vc2-cmps (into {} (map (juxt :sample-name identity) (:genotypes vc2)))
            score (/ (apply + (map #(calc-genotype-score % (get vc2-cmps (:sample-name %)))
                                   (:genotypes vc1)))
                     (:num-samples vc1))]
        (>= score score-thresh)))))

(defmethod compare-vcs :approximate
  ^{:doc "Provide approximate comparisons between variants, handling cases
          like het versus homozygous variant calls and indels with
          different overlapping calls. The goal is to identify almost-match
          cases which are useful for variant evidence."}
  [vc1 vc2 params]
  (when (= (:type vc1) (:type vc2))
    (compare-vcs vc1 vc2 (assoc params :compare-approach
                                (str "approximate-" (-> vc1 :type string/lower-case))))))

(defmethod compare-vcs :approximate-indel
  ^{:doc "Approximate comparisons for indels, allowing overlapping
          indels to count as concordant."}
  [vc1 vc2 params]
  {:pre [(every? #(= 1 (:num-samples %)) [vc1 vc2])]}
  (letfn [(all-alleles [x]
            (map #(.getBaseString %) (cons (:ref-allele x) (-> x :genotypes first :alleles))))]
    (let [vc1-alleles (all-alleles vc1)
          vc2-alleles (all-alleles vc2)]
      (and (contains? (set (range (dec (:start vc1)) (:end vc1))) (dec (:start vc2)))
           (or (every? #(= 1 (count (first %))) [vc1-alleles vc2-alleles])
               (every? #(> (count (first %)) 1) [vc1-alleles vc2-alleles]))))))

(defmethod compare-vcs :approximate-snp
  ^{:doc "Approximate comparisons for SNPs, allowing matching het/hom calls."}
  [vc1 vc2 params]
  {:pre [(every? #(= 1 (:num-samples %)) [vc1 vc2])]}
  (when (same-vc-coords? vc1 vc2)
    (not (empty?
          (->> [vc1 vc2]
               (map #(-> % :genotypes first :alleles set))
               (apply intersection))))))

(defmethod compare-vcs :default
  ^{:doc "Provide exact comparisons for variants, requiring identical
          base coordinates and reference and identical allele calls."}
  [vc1 vc2 params]
  {:pre [(every? #(= 1 (:num-samples %)) [vc1 vc2])]}
  (when (same-vc-coords? vc1 vc2) 
    (apply = (map #(-> % :genotypes first :alleles) [vc1 vc2]))))

(defn find-concordant-vcs
  "Top level comparison of variant contexts: check if any vc2s match vc1.
   Flexible handles different comparisons with `compare-vcs`"
  [vc1 vc2-checks params]
  (letfn [(are-concordant? [vc1 vc2]
            (when (compare-vcs vc1 vc2 params)
              true))]
    (let [vc2-groups (group-by (partial are-concordant? vc1) vc2-checks)]
      [(get vc2-groups true [])
       (get vc2-groups nil [])])))

(defn- add-cmp-kw
  [xs kw]
  (partition 2 (interleave (repeat kw) xs)))

(defn- compare-vc-w-iter
  "Compare variant context with second list of variants.
  Assumes sorted vc2-iter by position, returning any variants in the region
  that don't match."
  [vc1 vc2-iter cmp-kws params]
  {:pre [(= 3 (count cmp-kws))]}
  (letfn [(less-than-vc? [vc1 vc2]
            (and (= (:chr vc2) (:chr vc1))
                 (<= (:start vc2) (:end vc1))))]
    (let [[cur-vc2-iter rest-vc2-iter] (split-with (partial less-than-vc? vc1) vc2-iter)
          [vc2-extras vc2-checks] (split-with #(< (:start %) (:start vc1)) cur-vc2-iter)
          [vc2-matches vc2-continues] (find-concordant-vcs vc1 vc2-checks params)]
      {:cur-cmps (concat (add-cmp-kw vc2-extras (last cmp-kws))
                         [(if (seq vc2-matches)
                            [(first cmp-kws) vc1]
                            [(second cmp-kws) vc1])])
       :cur-vc2-iter (concat vc2-continues rest-vc2-iter)})))

(defn- compare-two-vc-iters
  "Lazy comparison of two sets of variants. Assumes identical ordering."
  [vc1-iter vc2-iter cmp-kws params]
  (lazy-seq
   (if-let [vc1 (first vc1-iter)]
     (let [{:keys [cur-cmps cur-vc2-iter]} (compare-vc-w-iter vc1 vc2-iter cmp-kws params)]
       (concat cur-cmps (compare-two-vc-iters (rest vc1-iter) cur-vc2-iter cmp-kws params)))
     (add-cmp-kw vc2-iter (last cmp-kws)))))

(defn compare-two-vcf-flexible
  "Compare two variant input files, with flexible matching conditions.
   TODO: restrict comparison by intervals."
  [c1 c2 exp config]
  (let [out-files (get-cmp-outfiles c1 c2 exp config)]
    (when (itx/needs-run? (vals out-files))
      (with-open [c1-iter (gvc/get-vcf-iterator (:file c1) (:ref exp))
                  c2-iter (gvc/get-vcf-iterator (:file c2) (:ref exp))]
        (gvc/write-vcf-w-template (:file c1) out-files
                                  (compare-two-vc-iters (gvc/parse-vcf c1-iter)
                                                        (gvc/parse-vcf c2-iter)
                                                        (keys out-files)
                                                        (get exp :params {}))
                                  (:ref exp))))
    {:c-files out-files :c1 c1 :c2 c2 :exp exp :dir (:dir config)}))
