require_relative "lib/blindrelay/version"

Gem::Specification.new do |spec|
  spec.name = "blindrelay"
  spec.version = Blindrelay::VERSION
  spec.summary = "Official Ruby SDK for the Blindrelay transactional email API."
  spec.authors = ["Blindrelay"]
  spec.homepage = "https://blindrelay.app"
  spec.license = "MIT"
  spec.required_ruby_version = ">= 3.0"
  spec.files = Dir["lib/**/*.rb", "README.md", "LICENSE"]
end
