(defproject lein-rpmbuild "0.1.7-SNAPSHOT"
  :description "Leiningen plugin for building an RPM package"
  :url "http://github.com/carlzhc/lein-rpmbuild"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :eval-in-leiningen true
  :dependencies [[carlzhc/lein-tar "3.3.0.1"]]
  :release-tasks
  [["vcs" "assert-committed"]
  ["change" "version" "leiningen.release/bump-version" "release"]
  ["vcs" "commit"]
  ["vcs" "tag" "v" "--no-sign"]
  ["deploy" "clojars"]
  ["change" "version" "leiningen.release/bump-version"]
  ["vcs" "commit"]
  ["vcs" "push"]])
