#
# Copyright 2015-2016 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import os
import subprocess
import httplib
from wskutil import request

#
# 'wsk sdk' CLI
#
class Sdk:

    def __init__(self):
        0 # empty body not allowed

    def getCommands(self, parser, props):
        sdkParser = parser.add_parser('sdk', help='work with the SDK')
        subcmds = sdkParser.add_subparsers(title='available commands', dest='subcmd')
        installParser = subcmds.add_parser('install', help='install artifacts')
        installParser.add_argument('component', help='component to be installed: {docker,swift,iOS}')

    def cmd(self, args, props):
        if args.subcmd == 'install':
            return self.installCmd(args, props)
        else:
            print 'Unknown SDK command:', args.subcmd

    def installCmd(self, args, props):
        if args.component == 'docker':
            return self.dockerDownload(args, props)
        if args.component == 'iOS':
            return self.iosStarterAppDownload(args, props)
        elif args.component == 'swift':
            return self.swiftDownload(args, props)
        else:
            print 'Unknown SDK component:', args.component

    def swiftDownload(self, args, props):
        print "Swift SDK coming soon"

    def dockerDownload(self, args, props):
        tarFile = 'blackbox-0.1.0.tar.gz'
        blackboxDir = 'dockerSkeleton'
        if os.path.exists(tarFile):
            print('The path ' + tarFile + ' already exists.  Please delete it and retry.')
            return -1
        if os.path.exists(blackboxDir):
            print('The path ' + blackboxDir + ' already exists.  Please delete it and retry.')
            return -1
        url = 'https://%s/%s' % (props['apihost'], tarFile)
        try:
            res = request('GET', url)
            if res.status == httplib.OK:
                with open(tarFile, 'wb') as f:
                    f.write(res.read())
        except:
            print('Download of docker skeleton failed.')
            return -1
        rc = subprocess.call(['tar', 'pxf', tarFile])
        if (rc != 0):
            print('Could not install docker skeleton.')
            return -1
        rc = subprocess.call(['rm', tarFile])
        print('\nThe docker skeleton is now installed at the current directory.')
        return 0

    def iosStarterAppDownload(self, args, props):
        zipFile = 'OpenWhiskIOSStarterApp.zip'
        if os.path.exists(zipFile):
            print('The path ' + zipFile + ' already exists.  Please delete it and retry.')
            return -1
        url = 'https://%s/%s' % (props['apihost'], zipFile)
        try:
            res = request('GET', url)
            if res.status == httplib.OK:
                with open(zipFile, 'wb') as f:
                    f.write(res.read())

        except IOError as e:
            print('Download of OpenWhisk iOS starter app failed.')
            return -1
        print('\nDownloaded OpenWhisk iOS starter app. Unzip ' + zipFile + ' and open the project in Xcode.')
        return 0

