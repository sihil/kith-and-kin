#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
AWS_DEFAULT_PROFILE=kk
AWS_DEFAULT_REGION=eu-west-2
PLAMBDA_BUCKET="kithandkin"
PLAMBDA_KEY="application/plambda/kith-and-kin.zip"
REMOTE_PLAMBDA_LOCATION="s3://${PLAMBDA_BUCKET}/${PLAMBDA_KEY}"

. $(brew --prefix nvm)/nvm.sh
nvm use 6
yarn
yarn build
yarn build-pay

# build the lambda zip
sbt universal:packageBin

# upload the assets
ASSETS=${DIR}/target/plamda-assets
mkdir -p ${ASSETS}
rm -rf ${ASSETS}/*
unzip ${DIR}/target/scala-2.11/kith-and-kin_2.11-1.0-web-assets.jar -d ${ASSETS}/
aws s3 sync --exclude .DS_Store ${ASSETS}/public/ s3://kithandkin-plambda-assets/public/assets/

# copy to S3
aws s3 cp ${DIR}/target/universal/kith-and-kin.zip ${REMOTE_PLAMBDA_LOCATION}

# update the lambda function
aws lambda update-function-code --function-name kithandkin-plambda \
                                --s3-bucket ${PLAMBDA_BUCKET} \
                                --s3-key ${PLAMBDA_KEY}

#
echo
echo "Successful deployment!"