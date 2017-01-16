#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
AWS_DEFAULT_PROFILE=kk
AWS_DEFAULT_REGION=eu-west-2
REMOTE_DEB_LOCATION="s3://kithandkin/application/kith-and-kin_1.0_all.deb"

# build the deb
sbt debian:packageBin

# copy to S3
aws s3 cp ${DIR}/target/kith-and-kin_1.0_all.deb ${REMOTE_DEB_LOCATION}

# find instance
INSTANCE_ID=$( aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names KithAndKin-AutoscalingGroup-7XNO9TRBKHH6 | jq -r .AutoScalingGroups[0].Instances[0].InstanceId )
HOSTNAME=$( aws ec2 describe-instances --instance-ids i-0d4b33ed3db5b3ae9 | jq -r .Reservations[0].Instances[0].PublicDnsName )

# update the instance
ssh -t ${HOSTNAME} "sudo aws --region eu-west-2 s3 cp ${REMOTE_DEB_LOCATION} /kithkin"
ssh -t ${HOSTNAME} "sudo dpkg -i /kithkin/kith-and-kin_1.0_all.deb"
ssh -t ${HOSTNAME} "sudo service kith-and-kin restart"

#
echo
echo "Successful deployment!"