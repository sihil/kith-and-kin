package helpers

import db.{InviteRepository, PaymentRepository}
import models.{Invite, Payment}
import play.api.cache.CacheApi

import scala.concurrent.duration._
import scala.language.postfixOps

class ListCache(cacheApi: CacheApi, inviteRepository: InviteRepository, paymentRepository: PaymentRepository) {
  def getInvites: List[Invite] = cacheApi.getOrElse("allInvites", 5 minutes){inviteRepository.getInviteList.toList}
  def getPayments: List[Payment] = cacheApi.getOrElse("allPayments", 5 minutes){paymentRepository.getPaymentList.toList}
  def clear(): Unit = {
    cacheApi.remove("allInvites")
    cacheApi.remove("allPayments")
  }
}
