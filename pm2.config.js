'use strict'

var path = require('path')
module.exports = {
  apps: [
    {
      name: 'Ataru docker images',
      script: 'docker-compose',
      interpreter: '/bin/sh',
      args: ['up'],
      cwd: __dirname,
      log_file: 'docker-compose.log',
      pid_file: '.docker-compose.pid',
      restart_delay: 4000,
      wait_ready: true,
      watch: false,
      exec_interpreter: "none",
      exec_mode: "fork"
    },
    {
      name: 'Ataru CSS compilation',
      script: 'lein',
      interpreter: '/bin/sh',
      args: ['less', 'auto'],
      cwd: __dirname,
      log_file: 'lein-less.log',
      pid_file: '.lein-less.pid',
      restart_delay: 4000,
      wait_ready: true,
      watch: false,
      exec_interpreter: "none",
      exec_mode: "fork"
    },
    {
      name: 'Ataru Virkailija frontend compilation (8350)',
      script: 'lein',
      interpreter: '/bin/sh',
      args: ['cljsbuild', 'auto', 'virkailija-dev'],
      cwd: __dirname,
      log_file: 'lein-cljsbuild-virkailija-dev.log',
      pid_file: '.lein-cljsbuild-virkailija-dev.pid',
      restart_delay: 4000,
      wait_ready: true,
      watch: false,
      exec_interpreter: "none",
      exec_mode: "fork"
    },
    {
      name: 'Ataru Hakija frontend compilation (8351)',
      script: 'lein',
      interpreter: '/bin/sh',
      args: ['cljsbuild', 'auto', 'hakija-dev'],
      cwd: __dirname,
      log_file: 'lein-cljsbuild-hakija-dev.log',
      pid_file: '.lein-cljsbuild-hakija-dev.pid',
      restart_delay: 4000,
      wait_ready: true,
      watch: false,
      exec_interpreter: "none",
      exec_mode: "fork"
    },
    {
      name: 'Ataru Hakija backend (8351)',
      script: 'lein',
      interpreter: '/bin/sh',
      args: ['hakija-dev'],
      cwd: __dirname,
      log_file: 'lein-hakija-dev.log',
      pid_file: '.lein-hakija-dev.pid',
      restart_delay: 4000,
      wait_ready: true,
      watch: false,
      exec_interpreter: "none",
      exec_mode: "fork"
    },
    {
      name: 'Ataru Virkailija backend (8350)',
      script: 'lein',
      interpreter: '/bin/sh',
      args: ['virkailija-dev'],
      cwd: __dirname,
      log_file: 'lein-virkailija-dev.log',
      pid_file: '.lein-virkailija-dev.pid',
      restart_delay: 4000,
      wait_ready: true,
      watch: false,
      exec_interpreter: "none",
      exec_mode: "fork"
    },
    {
      name: 'Ataru Figheel',
      script: 'lein',
      interpreter: '/bin/sh',
      args: ['figwheel', 'virkailija-dev', 'hakija-dev'],
      cwd: __dirname,
      log_file: 'lein-virkailija-dev.log',
      pid_file: '.lein-virkailija-dev.pid',
      restart_delay: 4000,
      wait_ready: true,
      watch: false,
      exec_interpreter: "none",
      exec_mode: "fork"
    },

  ]
}
