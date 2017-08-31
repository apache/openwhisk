FROM php:7.1-alpine

RUN \
    
    apk update && apk upgrade && \

    # install dependencies
   apk add \
       postgresql-dev \
       icu \
       icu-libs \
       icu-dev \
       freetype-dev \
       libjpeg-turbo-dev \
       libpng-dev \
       libxml2-dev \

   && \

   # install useful PHP extensions
   docker-php-ext-install \
       opcache \
       mysqli \
       pdo_mysql \
       pdo_pgsql \
       intl \
       bcmath \
       zip \
       gd \
       soap

# install composer
RUN curl -s -f -L -o /tmp/installer.php https://getcomposer.org/installer \
    && php /tmp/installer.php --no-ansi --install-dir=/usr/bin --filename=composer \
    && composer --ansi --version --no-interaction

# create src directory to store action files
RUN mkdir -p /action/src

# install Composer dependencies
COPY composer.json /action
RUN cd /action && /usr/bin/composer install --no-plugins --no-scripts --prefer-dist --no-dev -o && rm composer.lock

# copy required files
COPY router.php /action
COPY runner.php /action

# Run webserver on port 8080
EXPOSE 8080
CMD [ "php", "-S", "0.0.0.0:8080", "-d", "expose_php=0", "-d", "html_errors=0", "-d", "error_reporting=E_ALL", "/action/router.php" ]
