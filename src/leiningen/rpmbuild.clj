(ns leiningen.rpmbuild
  (:require [leiningen.core.main :as lein]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [leiningen.tar :refer [tar]]))

(defn- gitlog
  "Generate changelog from git log"
  []
  (let [result (shell/sh "git" "log" "--pretty=format:* %ad %an <%ae> - %h %n- %s%n" "--date=format:%a %b %d %Y")]
    (if (zero? (:exit result)) (:out result) nil)))

(defn- newlinejoin
  "Use newline to join elements in collection"
  [acol]
  (if (coll? acol)
    (str/join "\n" acol)
    acol))

(defn- tagformat
  [tagname tagval]
  (format "%-16s%s\n" (str tagname ":") tagval))

(defn- genspec
  "Generate a RPM spec file"
  [project
   {:keys [Name Version Release Summary Group License URL BuildArch BuildRoot Prefix Requires
           Source0 Source1 Source2 Source3 Source4 Source5 Source6 Source7 Source8 Source9
           %description %prep %install %clean %post %files %changelog %doc
           %define %undefine %global]
    :as options}]
  (let [pkg (io/file (:root project) "pkg")
        _ (.mkdir pkg)
        specfile (io/file pkg (str (or Name (:name project)) ".spec"))]
    (with-open [spec (io/writer specfile)]
      (when %global
        (doseq [line %global]
          (.write spec (format "%%global %s %s\n" (first line) (second line)))))
      (when %define
        (doseq [line %define]
          (.write spec (format "%%define %s %s\n" (first line) (second line)))))
      (when %undefine
        (doseq [line %undefine]
          (.write spec (format "%%undefine %s %s\n" (first line) (second line)))))
      (when (or %global %define %undefine) (.newLine spec))
      (.write spec (tagformat "Name" (or Name (:name project))))
      (.write spec (tagformat "Version" (or Version (:version project))))
      (.write spec (tagformat "Release" (or Release "1%{?dist}")))
      (.write spec (tagformat "Summary" (or Summary (newlinejoin (:description project)))))
      (.write spec (tagformat "Group" (or Group "Application")))
      (.write spec (tagformat "License" (or License (:name (:license project)))))
      (.write spec (tagformat "URL" (or URL (:url project))))
      (.write spec (tagformat "Source0" (or Source0 "%{name}-%{version}.tar.gz")))
      (doseq [n (range 1 10)]
        (when-let [v (get options (str "Source" n))]
          (.write spec (tagformat (str "Source" n) v))))
      (.write spec (tagformat "BuildRoot"
                           (or BuildRoot "%{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)")))
      (.write spec (tagformat "BuildArch" (or BuildArch "noarch")))

      (when Prefix
        (.write spec (tagformat "Prefix" Prefix)))
      
      (when Requires
        (doseq [r Requires]
          (.write spec (tagformat "Requires" r))))

      (.newLine spec)
      (.write spec (format "%%description\n%s\n"
                           (newlinejoin (or %description (:description project)))))

      (.newLine spec)
      (.write spec (format "%%prep\n%s\n"
                           (newlinejoin (or %prep ["%autosetup -v"]))))

      (.newLine spec)
      (.write spec (format "%%install\n%s\n"
                           (newlinejoin (or %install
                                            ["rm -f *.spec build.clj"
                                             (str "mkdir -p $RPM_BUILD_ROOT" Prefix)
                                             (str "cp -ar . $RPM_BUILD_ROOT" Prefix)]))))

      (.newLine spec)
      (.write spec (format "%%clean\n%s\n"
                           (newlinejoin (or %clean
                                            ["rm -rf $RPM_BUILD_ROOT"]))))

      (when %post
        (.newLine spec)
        (.write spec (format "%%post\n%s\n" (newlinejoin %post))))

      (.newLine spec)
      (.write spec (format "%%files\n%s\n"
                           (newlinejoin (or %files
                                            ["%defattr(-,root,root,-)"
                                             (str Prefix "/*")]))))
      (when %doc
        (.write spec (format "%%doc %s\n"
                             (newlinejoin %doc))))

      (when %changelog
        (.newLine spec)
        (if (= :gitlog %changelog)
          (.write spec (format "%%changelog\n%s\n" (gitlog)))
          (.write spec (format "%%changelog\n%s\n" (newlinejoin %changelog))))))
    
    (lein/info "Wrote" (.getCanonicalPath specfile))
    (assoc-in project [:rpmbuild :spec] (.getCanonicalPath specfile))))


(defn- maketarball
  [project & args]
  (let [tarball (apply tar project args)]
    (assoc-in project [:rpmbuild :tarball] tarball)))

(defn- buildrpm [proj act]
  (let [tarball (get-in proj [:rpmbuild :tarball])
        result (shell/sh "rpmbuild" act tarball)]
    (if (zero? (:exit result))
      (lein/info (:out result))
      (lein/warn (:err result)))))

(defn rpmbuild
  "Build RPM package from project's files"
  [project & args]
  (let [options (:rpmbuild project)]
    (condp = (first args)
      "-spec" (genspec project options)
      "-tar" (do (genspec project options) (maketarball project))
      "-ta" (do (-> project
                 (genspec options)
                 (maketarball)
                 (buildrpm "-ta")))
      "-tb" (do (-> project
                 (genspec options)
                 (maketarball)
                 (buildrpm "-tb")))
      "-ts" (do (-> project
                 (genspec options)
                 (maketarball)
                 (buildrpm "-ts")))
      (lein/warn "Error: one option needed from \"-ta\", \"-tb\", \"-ts\" or \"-spec\""))))
