;; Example project

(defproject lein-rpmbuild "0.1.1"
  :description "Leiningen plugin for building an RPM package"
  :url "http://github.com/carlzhc/lein-rpmbuild"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :eval-in-leiningen true
  :plugins [[carlzhc/lein-tar "3.3.0"]
            [lein-rpmbuild "0.1.1"]]
  :tar {:ubergar true
        :format :tar-gz}
  :rpmbuild {:%define [["__os_install_post" "%{nil}"]]
             :Requires ["java-1.8.0-openjdk"]
             :Prefix "/opt/%name"
             :%changelog :gitlog
             :%doc ["README.org"]})

