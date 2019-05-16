(ns leiningen.rpmbuild
  (:require [leiningen.core.main :as lein]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [leiningen.tar :as tar])
  (:import java.lang.System
           java.io.File))

(defn- file-exists?
  "Check file existence"
  [file]
  (.exists (File. file)))

(defn- file-in-path
  "Find the first file on each path included in the PATH environment variable"
  [file]
  (first (filter file-exists?
                   (map #(str % "/" file)
                        (str/split (System/getenv "PATH") #":")))))

(defn- git-version
  "Check git version"
  []
  (if-let [git (file-in-path "git")]
    (-> (shell/sh git "--version")
        (:out)
        (str/trim)
        (str/split #" ")
        (last))
    (lein/abort "Error: command not found: git")))

(defn- version-compare
  "Compare 2 version number in format x.y.z...
  Return -1 if v1 < v2, 0 if v1 = v2, 1 otherwise"
  [v1 v2]
  (loop [pv1 (str/split v1 #"[.]")
         pv2 (str/split v2 #"[.]")]
    (cond
      (= pv1 pv2) 0
      (nil? pv1) -1
      (nil? pv2) 1
      :else
      (let [iv1 (Integer. (first pv1))
            iv2 (Integer. (first pv2))]
        (cond
          (= iv1 iv2) (recur (next pv1) (next pv2))
          (< iv1 iv2) -1
          (> iv1 iv2) 1
          )))))

(defn- git-version-validate
  "Check the minimum required version of git"
  []
  (if-let [gitver (git-version)]
    (if (>= (version-compare gitver "2.6.0") 0) true
        (lein/abort "Error: git version: requires 2.6.0 or greater"))
    (lein/abort "Error: git version: unknown")))

(defn- gittag
  "Generate changelog from git annotated tags"
  []
  (git-version-validate)
  (let [result (shell/sh
                "git" "for-each-ref"
                (str "--format="
                     "%(if)%(taggerdate)%(then)"
                     "* %(taggerdate:format:%a %b %d %Y) %(taggername) "
                     "%(taggeremail) - %(refname:short)%0a"
                     "- %(subject) "
                     "%(body)%(end)")
                "refs/tags")]
    (if (zero? (:exit result)) (:out result)
        (lein/abort "Error: failed to run command: git for-each-ref"))))

(defn- gitlog
  "Generate changelog from git log"
  []
  (git-version-validate)
  (let [result (shell/sh "git" "log" "--pretty=format:* %ad %an <%ae> - %h %n- %s%n" "--date=format:%a %b %d %Y")]
    (if (zero? (:exit result)) (:out result)
        (lein/abort "Error: failed to run command: git log"))))

(defn- join-with
  [s acol]
  (if (coll? acol)
    (str/join s acol)
    acol))

(defn- join-with-newline
  "Use newline to join elements in collection"
  [acol]
  (join-with "\n" acol))

(defn- join-with-space
  "Use single space to join elements in collection"
  [acol]
  (join-with " " acol))

(defn- format-tag
  [tagname tagval]
  (format "%-16s%s\n" (str tagname ":") tagval))

(defn- gen-spec
  "Generate a RPM spec file"
  [project
   {:keys [Name Version Release Summary Group License URL BuildArch BuildRoot Prefix Requires
           Source Source0 Source1 Source2 Source3 Source4 Source5 Source6 Source7 Source8 Source9
           %description %prep %build %check %install %clean %post %files %changelog %doc %config %defattr
           %define %undefine %global]
    :as options}]
  (let [pkg (io/file (:root project) "pkg")
        _ (.mkdir pkg)
        specfile (io/file pkg (str (or Name (:name project)) ".spec"))]
    (with-open [spec (io/writer specfile)]
      (when %global
        (doseq [line (partition 2 %global)]
          (.write spec (format "%%global %s %s\n" (first line) (second line)))))
      (when %define
        (doseq [line (partition 2 %define)]
          (.write spec (format "%%define %s %s\n" (first line) (second line)))))
      (when %undefine
        (doseq [line %undefine]
          (.write spec (format "%%undefine %s\n" line))))
      (when (or %global %define %undefine) (.newLine spec))
      (.write spec (format-tag "Name" (or Name (:name project))))
      (.write spec (format-tag "Version" (or Version (:version project))))
      (.write spec (format-tag "Release" (or Release "1%{?dist}")))
      (.write spec (format-tag "Summary" (or Summary (join-with-newline (:description project)))))
      (.write spec (format-tag "Group" (or Group "Application")))
      (.write spec (format-tag "License" (or License (:name (:license project)))))
      (.write spec (format-tag "URL" (or URL (:url project))))
      (.write spec (format-tag "Source0" (or Source0 Source
                                             (str (get-in project [:tar :name]
                                                          (tar/release-name project))
                                                  "."
                                                  (str/replace
                                                   (name (get-in project [:tar :format] :tar))
                                                   \- \.)))))
      (doseq [n (range 1 10)]
        (when-let [v (get options (str "Source" n))]
          (.write spec (format-tag (str "Source" n) v))))
      (.write spec (format-tag "BuildRoot"
                               (or BuildRoot "%{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)")))
      (.write spec (format-tag "BuildArch" (or BuildArch "noarch")))

      (when Prefix
        (.write spec (format-tag "Prefix" Prefix)))

      (when Requires
        (doseq [r Requires]
          (.write spec (format-tag "Requires" r))))

      (.newLine spec)
      (.write spec (format "%%description\n%s\n"
                           (join-with-newline (or %description (:description project)))))

      (.newLine spec)
      (.write spec (format "%%prep\n%s\n"
                           (join-with-newline (or %prep ["%autosetup -v"]))))

      (when %build
        (.newLine spec)
        (.write spec (format "%%build\n%s\n"
                             (join-with-newline %build))))

      (when %check
        (.newLine spec)
        (.write spec (format "%%check\n%s\n"
                             (join-with-newline %check))))

      (.newLine spec)
      (.write spec
              (format
               "%%install\n%s\n"
               (join-with-newline
                (or %install
                    (->
                     ["rm -f *.spec build.clj"
                      (str "mkdir -p $RPM_BUILD_ROOT" Prefix)
                      (str "cp -pr . $RPM_BUILD_ROOT" Prefix)]
                     (concat
                      (when %doc
                        (map #(str "rm -f $RPM_BUILD_ROOT" Prefix "/" %)
                             (remove #(str/index-of % \/) %doc))))
                     (concat [(str  "find $RPM_BUILD_ROOT -type f -printf '/%%P\\n'"
                                    " > %{u2p:%{buildroot}}.filelist")]))))))

      (.newLine spec)
      (.write spec
              (format
               "%%clean\n%s\n"
               (join-with-newline
                (or %clean
                    ["rm -rf $RPM_BUILD_ROOT %{u2p:%{buildroot}}.filelist"]))))

      (when %post
        (.newLine spec)
        (.write spec (format "%%post\n%s\n" (join-with-newline %post))))

      (.newLine spec)
      (.write spec (format "%%files%s\n%%defattr(%s)\n%s\n"
                           (or %files " -f %{u2p:%{buildroot}}.filelist")
                           (join-with "," (or %defattr ["-" "root" "root" "-"]))
                           (join-with-newline (str %files))))
      (when %doc
        (doseq [doc %doc]
          (.write spec (format "%%doc %s\n"
                               (if (or (nil? Prefix)
                                       (str/starts-with? doc "/")
                                       (str/starts-with? doc "%{prefix}/")
                                       (not (str/index-of doc \/)))
                                 doc
                                 (str "%{prefix}/" doc))))))

      (when %config
        (.write spec (if (string? %config)
                       (format "%%config %s\n" %config)
                       (join-with-newline (map #(format "%%config %s" %) %config)))))

      (when %changelog
        (.newLine spec)
        (.write spec "%changelog\n")
        (.write spec
                (condp = %changelog
                  :gitlog (gitlog)
                  :gittag (gittag)
                  (join-with-newline %changelog)))))

    (lein/info "Wrote" (.getCanonicalPath specfile))
    (assoc-in project [:rpmbuild :spec] (.getCanonicalPath specfile))))


(defn- maketarball
  [project & args]
  (let [tarball (apply tar/tar project args)]
    (assoc-in project [:rpmbuild :_tarball] tarball)))

(defn- buildrpm [proj act]
  (let [tarball (get-in proj [:rpmbuild :_tarball])
        result (shell/sh "rpmbuild" act tarball)]
    (if (zero? (:exit result))
      (lein/info (:out result))
      (lein/abort (str "Error: command failed: rpmbuild\n" (:err result))))))

(defn rpmbuild
  "Build RPM package from project's files"
  [project & args]
  (let [options (:rpmbuild project)]
    (when-not (file-in-path "rpmbuild")
      (lein/abort "Error: command not found: rpmbuild"))
    (condp = (first args)
      "-spec" (gen-spec project options)
      "-tar" (-> project
                 (gen-spec options)
                 (maketarball))
      "-ta" (-> project
                (gen-spec options)
                (maketarball)
                (buildrpm "-ta"))
      "-tb" (-> project
                (gen-spec options)
                (maketarball)
                (buildrpm "-tb"))
      "-ts" (-> project
                (gen-spec options)
                (maketarball)
                (buildrpm "-ts"))
      (lein/warn "Error: option needed: one of \"-ta\", \"-tb\", \"-ts\" \"-spec\""))))
