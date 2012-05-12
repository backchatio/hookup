#!/bin/bash

# run-tests and publish jars
sbt test publish
# sbt test doc
# checkout repo again but with gh-pages branch
mkdir -p target
rm -rf target/site
# mkdir -p target/site
git clone git@github.com:backchatio/hookup.git target/site
# generate site 
cd src/main/site
jekyll
# generate jsdoc 
cd ../node
npm install
npm test #&& npm publish 
node_modules/jsdoc-toolkit/app/run.js -d=../../../target/site/jsdoc -t=node_modules/jsdoc-toolkit/templates/codeview lib
# generate yardocs 
# first write the yardocs
# generate scaladoc 
cd ../../..
mv target/scala-2.9.1/api target/site/api
# commit changes to gh-pages
# cd target/site
git add .
git commit -a -m 'new release'
git push
cd ../..

