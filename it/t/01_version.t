#!/usr/bin/perl

use It;
use Test::More tests => 2;
use strict;
use JMX::Jmx4Perl::Request;
use JMX::Jmx4Perl;
use Data::Dumper;

my $jmx = new It(verbose => 0)->jmx4perl;

my $resp = $jmx->request(new JMX::Jmx4Perl::Request(VERSION));
my $value = $resp->{value};
my $version_exp = $JMX::Jmx4Perl::VERSION;
my ($base,$ext) = ($1,$3) if $version_exp =~ /^([\d.]+)(_(\d+))?$/;
$base = $base . ".0" unless $base =~ /^\d+\.\d+\.\d+$/;
$version_exp = $base . ($ext ? ".M" . $ext : "");
is($value->{agent},$version_exp,"Version " . $version_exp);
ok($value->{protocol} > 0,"Protocol version " . $value->{protocol});
#print Dumper(\@resps);

