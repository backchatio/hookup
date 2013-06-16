#!/bin/bash

FILE=$1
cp $FILE ${FILE}.orig

sed 's/import akka.util.{ Duration => AkkaDuration }/import scala.concurrent.duration.{ Duration => AkkaDuration }/' ${FILE}.orig |
sed 's/akka.util.Duration/scala.concurrent.duration.Duration/' |
sed 's/akka.dispatch.Future/scala.concurrent.Future/' | 
sed 's/akka.dispatch.{ Promise, ExecutionContext, Future }/scala.concurrent.{ Promise, ExecutionContext, Future }/' |
sed 's/akka.dispatch.{/scala.concurrent.{/' |
sed 's/akka.jsr166y.ForkJoinPool/scala.concurrent.forkjoin.ForkJoinPool/' |
sed 's/akka.util.{ Duration, Timeout }/scala.concurrent.duration.Duration/' |
sed 's/import akka.util.duration/import scala.concurrent.duration/' |
sed 's/akka.util.duration/scala.concurrent.duration/g' | 
sed 's/org.scalaquery.ql.ForeignKeyAction/scala.slick.lifted.ForeignKeyAction/g' |
sed 's/import org.scalaquery.session.Database.threadLocalSession/import scala.slick.driver.PostgresDriver.simple._; import scala.slick.session.Database.threadLocalSession/' | 
sed 's/org.scalaquery.session.Database.threadLocalSession/scala.slick.session.Database.threadLocalSession/' |
#import org.scalaquery.session.Database.threadLocalSession
#sed 's/import org.scalaquery.session.Database.threadLocalSession/import Database.threadLocalSession/' |
sed 's/import org.scalaquery.ql.basic.{ BasicTable => Table }/import scala.slick.driver.PostgresDriver.simple.Table/' |
sed 's/import org.scalaquery.simple.{GetResult, StaticQuery => Q}/import scala.slick.jdbc.{GetResult, StaticQuery => Q}/' |

sed 's/org.scalaquery.simple/scala.slick.jdbc/' |
#sed 's/org.scalaquery.session.Database/scala.slick.session.Database/' |
sed 's/import org.scalaquery.session/import scala.slick.session/g' |
sed 's/import org.scalaquery.ql.DDL/import scala.slick.lifted.DDL/' |
sed 's/import org.scalaquery.ql.basic.BasicProfile/import scala.slick.driver.BasicProfile/g' |
sed 's/org.scalaquery.ql.basic.BasicDriver/scala.slick.driver.PostgresDriver.simple.slickDriver/' |
sed 's/import org.scalaquery.ql.basic._/import scala.slick.driver._/' |
#sed 's/org.scalaquery.ql.basic.Basic/scala.slick.driver.Basic/' |
sed 's/org.scalaquery.ql/scala.slick.lifted/' |
sed 's/channelID.name/channelID/g' | sed 's/ts.name/ts/g' |
sed 's/import org.scalaquery.simple/import scala.slick.jdbc/' > $FILE
