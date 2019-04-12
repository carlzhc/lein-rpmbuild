(ns leiningen.rpmbuild
  (:require [leiningen.core.main :as lein]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

(defn- gitlog
  "Generate changelog from git log.
  git log --pretty=format:'* %ad %an <%ae> %h %n- %s%n'"
  []
  (let [result (shell/sh "git" "log" "--pretty=format:* %ad %an <%ae> %h %n- %s%n")]
    (if (zero? (:exit result)) (:out result) nil)))


(defn- genspec
  "Generate a RPM spec file"
  [project
   {:keys [Name Version Release Summary Group License URL BuildArch BuildRoot Requires
           Source0 Source1 Source2 Source3 Source4 Source5 Source6 Source7 Source8 Source9
           %description %prep %install %clean %post %files %changelog %define]
    :as options}]
  (let [specfile (str "target/" (or Name (:name project)) ".spec")]
    (with-open [spec (io/writer specfile)]
      (when %define
        (doseq [line %define]
          (.write spec (format "%%define %s %s\n" (first line) (second line)))))
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
      (.write spec (format "%%install\n%s\n" (or %install "cp -r . $RPM_BUILD_ROOT/")))

      (.newLine spec)
      (.write spec (format "%%clean\n%s\n" (or %clean "rm -rf $RPM_BUILD_ROOT")))

      (when %post
        (.newLine spec)
        (.write spec (format "%%post\n%s\n" %post)))

      (.newLine spec)
      (.write spec (format "%%files\n%%defattr(-,root,root,-)\n%s\n" (or (not= "" (str/join "\n" %files)) "/*")))

      (when %changelog
        (.newLine spec)
        (if (= :git %changelog)
          (.write spec (format "%%changelog\n%s\n" (gitlog)))
          (.write spec (format "%%changelog\n%s\n" (str/join "\n\n" %changelog)))))
      
      )
    (leiningen.core.main/info "Wrote" specfile)))



(defn- buildall
  [proj opts]
  )

(defn- buildbin [proj opts])
(defn- buildsrc [proj opts])


(defn rpmbuild
  "Build rpm"
  [project & args]
  (let [options (:rpmbuild project)]
    (condp = (first args)
      "-spec" (genspec project options)
      "-ba" (buildall project options)
      "-bb" (buildbin project options)
      "-bs" (buildsrc project options)
      (lein/warn "Error: one option needed from \"-ba\", \"-bb\", \"-bs\" or \"-spec\""))))
