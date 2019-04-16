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


(defn- genspec
  "Generate a RPM spec file"
  [project
   {:keys [Name Version Release Summary Group License URL BuildArch BuildRoot Requires
           Source0 Source1 Source2 Source3 Source4 Source5 Source6 Source7 Source8 Source9
           %description %prep %install %clean %post %files %changelog
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
      (.newLine spec)
      (.write spec (format "Name:        %s\n" (or Name (:name project))))
      (.write spec (format "Version:     %s\n" (or Version (:version project))))
      (.write spec (format "Release:     %s\n" (or Release "1%{?dist}")))
      (.write spec (format "Summary:     %s\n" (or Summary (:description project))))
      (.write spec (format "Group:       %s\n" (or Group "Application")))
      (.write spec (format "License:     %s\n" (or License (:name (:license project)))))
      (.write spec (format "URL:         %s\n" (or URL (:url project))))
      (.write spec (format "Source0:     %s\n" (or Source0 "%{name}-%{version}.tar.gz")))
      (doseq [n (range 1 10)]
        (when-let [v (get options (str "Source" n))]
          (.write spec (format "Source%d: %s\n" n v))))
      (.write spec (format "BuildRoot:   %s\n"
                           (or BuildRoot "%{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)")))
      (.write spec (format "BuildArch:   %s\n" "noarch"))
      (when Requires
        (doseq [r Requires]
          (.write spec (format "Requires:    %s\n" r))))

      (.newLine spec)
      (.write spec (format "%%description\n%s\n" (or %description (:description project))))

      (.newLine spec)
      (.write spec (format "%%prep\n%s\n" (or %prep "%autosetup -v")))

      (.newLine spec)
      (.write spec (format "%%install\n%s\n" (or %install "rm -f *.spec build.clj; cp -r . $RPM_BUILD_ROOT/")))

      (.newLine spec)
      (.write spec (format "%%clean\n%s\n" (or %clean "rm -rf $RPM_BUILD_ROOT")))

      (when %post
        (.newLine spec)
        (.write spec (format "%%post\n%s\n" %post)))

      (.newLine spec)
      (.write spec (format "%%files\n%%defattr(-,root,root,-)\n%s\n" (or (not= "" (str/join "\n" %files)) "/*")))

      (when %changelog
        (.newLine spec)
        (if (= :gitlog %changelog)
          (.write spec (format "%%changelog\n%s\n" (gitlog)))
          (.write spec (format "%%changelog\n%s\n" (str/join "\n\n" %changelog)))))
      
      )
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
